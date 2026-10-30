"""
MedicalStaffScheduleTracker - SQLite Database Persistence Layer
Ensures all schedules, reviews, approvals, manual duties, and jobs survive server restarts.
"""

from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import sqlite3
from typing import Optional, Dict, Any, List

DB_PATH = Path(os.getenv("DATABASE_PATH", Path(__file__).parent / "tracker.db"))


def get_db_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    """Initializes SQLite schema and seeds staff users if not present."""
    conn = get_db_connection()
    cur = conn.cursor()

    cur.execute("""
    CREATE TABLE IF NOT EXISTS users (
        staff_id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        role TEXT NOT NULL,
        department TEXT,
        floor TEXT,
        password_hash TEXT NOT NULL
    );
    """)

    cur.execute("""
    CREATE TABLE IF NOT EXISTS draft_schedules (
        schedule_id TEXT PRIMARY KEY,
        staff_id TEXT NOT NULL,
        month TEXT NOT NULL,
        is_approved INTEGER DEFAULT 0,
        approved_at TEXT,
        approved_by TEXT,
        data_json TEXT NOT NULL,
        created_at TEXT NOT NULL
    );
    """)

    cur.execute("""
    CREATE TABLE IF NOT EXISTS approved_schedules (
        key TEXT PRIMARY KEY, -- staff_id_month
        staff_id TEXT NOT NULL,
        month TEXT NOT NULL,
        schedule_id TEXT,
        data_json TEXT NOT NULL,
        approved_at TEXT NOT NULL,
        approved_by TEXT NOT NULL
    );
    """)

    cur.execute("""
    CREATE TABLE IF NOT EXISTS manual_duties (
        id TEXT PRIMARY KEY,
        staff_id TEXT NOT NULL,
        date TEXT NOT NULL,
        shift TEXT NOT NULL,
        department TEXT NOT NULL,
        floor TEXT NOT NULL,
        notes TEXT,
        created_at TEXT NOT NULL
    );
    """)

    cur.execute("""
    CREATE TABLE IF NOT EXISTS roster_jobs (
        job_id TEXT PRIMARY KEY,
        schedule_id TEXT NOT NULL,
        staff_id TEXT NOT NULL,
        month TEXT NOT NULL,
        status TEXT NOT NULL,
        error TEXT,
        created_at TEXT NOT NULL
    );
    """)

    # Seed default users
    default_users = [
        ("ABHL341", "Akash Kumar", "staff", "ICU / Emergency", "Floor 2", "password123"),
        ("ABHL217", "Pooja Sharma", "staff", "General Medicine", "Floor 3", "password123"),
        ("SUPER01", "Dr. Sarah Admin", "supervisor", "Medical Administration", "Floor 1", "admin123"),
    ]
    for uid, name, role, dept, floor, pwd in default_users:
        cur.execute("SELECT 1 FROM users WHERE staff_id = ?", (uid,))
        if not cur.fetchone():
            pwd_hash = hashlib.sha256(pwd.encode()).hexdigest()
            cur.execute(
                "INSERT INTO users (staff_id, name, role, department, floor, password_hash) VALUES (?, ?, ?, ?, ?, ?)",
                (uid, name, role, dept, floor, pwd_hash)
            )

    conn.commit()
    conn.close()


def get_user_by_id(staff_id: str) -> Optional[Dict[str, Any]]:
    conn = get_db_connection()
    cur = conn.cursor()
    cur.execute("SELECT * FROM users WHERE staff_id = ?", (staff_id.strip().upper(),))
    row = cur.fetchone()
    conn.close()
    if not row:
        return None
    return dict(row)


def authenticate_user_db(username_or_id: str, password: str) -> Optional[Dict[str, Any]]:
    user = get_user_by_id(username_or_id)
    if not user:
        return None
    pwd_hash = hashlib.sha256(password.encode()).hexdigest()
    if user["password_hash"] == pwd_hash:
        return {
            "staff_id": user["staff_id"],
            "name": user["name"],
            "role": user["role"],
            "department": user["department"],
            "floor": user["floor"],
        }
    return None


def save_draft_schedule(
    schedule_id: str,
    staff_id: str,
    month: str,
    reconstruction: Dict[str, Any],
    is_approved: bool = False,
    approved_at: Optional[str] = None,
    approved_by: Optional[str] = None,
) -> None:
    conn = get_db_connection()
    cur = conn.cursor()
    created_at = datetime.utcnow().isoformat()
    cur.execute("""
    INSERT OR REPLACE INTO draft_schedules
    (schedule_id, staff_id, month, is_approved, approved_at, approved_by, data_json, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        schedule_id,
        staff_id,
        month,
        1 if is_approved else 0,
        approved_at,
        approved_by,
        json.dumps(reconstruction),
        created_at,
    ))
    conn.commit()
    conn.close()


def get_draft_schedule(schedule_id: str) -> Optional[Dict[str, Any]]:
    conn = get_db_connection()
    cur = conn.cursor()
    cur.execute("SELECT * FROM draft_schedules WHERE schedule_id = ?", (schedule_id,))
    row = cur.fetchone()
    conn.close()
    if not row:
        return None
    return {
        "schedule_id": row["schedule_id"],
        "staff_id": row["staff_id"],
        "month": row["month"],
        "is_approved": bool(row["is_approved"]),
        "approved_at": row["approved_at"],
        "approved_by": row["approved_by"],
        "reconstruction": json.loads(row["data_json"]),
    }


def update_draft_schedule_cell(
    schedule_id: str,
    day_number: int,
    new_shift: Optional[str] = None,
    notes: Optional[str] = None,
) -> bool:
    draft = get_draft_schedule(schedule_id)
    if not draft:
        return False

    recon = draft["reconstruction"]
    entries = recon.get("entries", [])
    updated = False
    for entry in entries:
        if entry.get("day_number") == day_number:
            if new_shift:
                entry["normalized_shift"] = new_shift.upper()
                entry["review_status"] = "confirmed"
                entry["review_reason"] = f"Confirmed by user ({notes or 'Manual review'})"
            updated = True
            break

    if updated:
        conn = get_db_connection()
        cur = conn.cursor()
        cur.execute(
            "UPDATE draft_schedules SET data_json = ? WHERE schedule_id = ?",
            (json.dumps(recon), schedule_id)
        )
        conn.commit()
        conn.close()
    return updated


def approve_draft_schedule(schedule_id: str, approved_by: str) -> Optional[Dict[str, Any]]:
    draft = get_draft_schedule(schedule_id)
    if not draft:
        return None

    approved_at = datetime.utcnow().isoformat()
    staff_id = draft["staff_id"]
    month = draft["month"]
    key = f"{staff_id}_{month}"

    conn = get_db_connection()
    cur = conn.cursor()

    # Update draft
    cur.execute("""
    UPDATE draft_schedules
    SET is_approved = 1, approved_at = ?, approved_by = ?
    WHERE schedule_id = ?
    """, (approved_at, approved_by, schedule_id))

    # Save to approved schedules
    cur.execute("""
    INSERT OR REPLACE INTO approved_schedules
    (key, staff_id, month, schedule_id, data_json, approved_at, approved_by)
    VALUES (?, ?, ?, ?, ?, ?, ?)
    """, (
        key,
        staff_id,
        month,
        schedule_id,
        json.dumps(draft["reconstruction"]),
        approved_at,
        approved_by,
    ))

    conn.commit()
    conn.close()

    draft["is_approved"] = True
    draft["approved_at"] = approved_at
    draft["approved_by"] = approved_by
    return draft


def get_approved_schedule(staff_id: str, month: str) -> Optional[Dict[str, Any]]:
    key = f"{staff_id}_{month}"
    conn = get_db_connection()
    cur = conn.cursor()
    cur.execute("SELECT * FROM approved_schedules WHERE key = ?", (key,))
    row = cur.fetchone()
    conn.close()
    if not row:
        return None
    return {
        "staff_id": row["staff_id"],
        "month": row["month"],
        "schedule_id": row["schedule_id"],
        "is_approved": True,
        "approved_at": row["approved_at"],
        "approved_by": row["approved_by"],
        "reconstruction": json.loads(row["data_json"]),
    }


def add_manual_duty(
    duty_id: str,
    staff_id: str,
    date_str: str,
    shift: str,
    department: str,
    floor: str,
    notes: Optional[str] = None,
) -> Dict[str, Any]:
    conn = get_db_connection()
    cur = conn.cursor()
    created_at = datetime.utcnow().isoformat()
    cur.execute("""
    INSERT INTO manual_duties (id, staff_id, date, shift, department, floor, notes, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """, (duty_id, staff_id, date_str, shift, department, floor, notes, created_at))
    conn.commit()
    conn.close()
    return {
        "id": duty_id,
        "staff_id": staff_id,
        "date": date_str,
        "shift": shift,
        "department": department,
        "floor": floor,
        "notes": notes,
        "created_at": created_at,
    }


def get_manual_duties(staff_id: str) -> List[Dict[str, Any]]:
    conn = get_db_connection()
    cur = conn.cursor()
    cur.execute("SELECT * FROM manual_duties WHERE staff_id = ? ORDER BY date ASC", (staff_id,))
    rows = cur.fetchall()
    conn.close()
    return [dict(r) for r in rows]


def save_job(job_id: str, schedule_id: str, staff_id: str, month: str, status: str, error: Optional[str] = None):
    conn = get_db_connection()
    cur = conn.cursor()
    created_at = datetime.utcnow().isoformat()
    cur.execute("""
    INSERT OR REPLACE INTO roster_jobs (job_id, schedule_id, staff_id, month, status, error, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?)
    """, (job_id, schedule_id, staff_id, month, status, error, created_at))
    conn.commit()
    conn.close()


def get_job(job_id: str) -> Optional[Dict[str, Any]]:
    conn = get_db_connection()
    cur = conn.cursor()
    cur.execute("SELECT * FROM roster_jobs WHERE job_id = ?", (job_id,))
    row = cur.fetchone()
    conn.close()
    if not row:
        return None
    return dict(row)
