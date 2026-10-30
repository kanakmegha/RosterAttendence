package com.example.medicalstaffscheduletracker.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.medicalstaffscheduletracker.data.model.*
import org.json.JSONArray

class ScheduleDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "medical_schedule_tracker.db"
        const val DATABASE_VERSION = 1

        const val TABLE_SCHEDULES = "schedules"
        const val TABLE_ENTRIES = "schedule_entries"
        const val TABLE_MANUAL_DUTIES = "manual_duties"
        const val TABLE_PREFERENCES = "user_preferences"
        const val TABLE_REVISIONS = "revision_history"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SCHEDULES (
                id TEXT PRIMARY KEY,
                month TEXT NOT NULL,
                staff_id TEXT NOT NULL,
                staff_name TEXT,
                department TEXT,
                floor TEXT,
                weekly_off TEXT,
                is_approved INTEGER DEFAULT 0,
                approved_at TEXT,
                is_confusable INTEGER DEFAULT 0,
                confusable_message TEXT,
                created_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_ENTRIES (
                id TEXT PRIMARY KEY,
                schedule_id TEXT NOT NULL,
                day_number INTEGER NOT NULL,
                date TEXT NOT NULL,
                weekday TEXT NOT NULL,
                raw_value TEXT,
                normalized_shift TEXT,
                review_status TEXT NOT NULL,
                review_reason TEXT,
                confidence REAL,
                source_type TEXT NOT NULL,
                inference_rule TEXT,
                supporting_dates TEXT,
                supporting_raw_tokens TEXT,
                section_start_day INTEGER,
                section_end_day INTEGER,
                FOREIGN KEY (schedule_id) REFERENCES $TABLE_SCHEDULES (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MANUAL_DUTIES (
                id TEXT PRIMARY KEY,
                date TEXT NOT NULL,
                shift TEXT NOT NULL,
                department TEXT,
                floor TEXT,
                notes TEXT,
                created_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_PREFERENCES (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_REVISIONS (
                id TEXT PRIMARY KEY,
                original_schedule_id TEXT NOT NULL,
                revised_schedule_id TEXT NOT NULL,
                diff_summary TEXT,
                created_at INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_REVISIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PREFERENCES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MANUAL_DUTIES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SCHEDULES")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
