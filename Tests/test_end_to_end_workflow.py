"""
Complete End-to-End Workflow Integration Test
Verifies all 8 steps of the mobile schedule workflow:
1. User logs in with credentials and receives verified JWT token.
2. User uploads roster file to /api/roster/upload.
3. Backend extracts schedule using OCR table reconstruction + 10-day section inference.
4. Returns reviewable DraftSchedule (is_approved=False).
5. Unresolved cells and confusable ID warnings are surfaced.
6. User reviews and confirms an individual cell.
7. Draft remains INACTIVE until authorized supervisor approval.
8. Approved schedule is retrieved from SQLite and displayed in calendar view.
"""

from pathlib import Path
import pytest
from server import app
from db import init_db, get_db_connection, get_draft_schedule, get_approved_schedule
from auth import STAFF_USERS

ROSTER_IMAGE_PATH = Path("/Users/skanakmegha/Downloads/roster.jpeg")


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client


@pytest.fixture(autouse=True)
def clean_db():
    init_db()
    conn = get_db_connection()
    cur = conn.cursor()
    cur.execute("DELETE FROM draft_schedules")
    cur.execute("DELETE FROM approved_schedules")
    cur.execute("DELETE FROM manual_duties")
    cur.execute("DELETE FROM roster_jobs")
    conn.commit()
    conn.close()
    yield


def test_complete_end_to_end_workflow(client):
    # =========================================================================
    # Step 1: User logs in (Akash Kumar, Staff ID: ABHL341)
    # =========================================================================
    login_res = client.post(
        "/api/auth/login",
        json={"staff_id": "ABHL341", "password": "password123"},
    )
    assert login_res.status_code == 200
    login_data = login_res.get_json()
    assert login_data["success"] is True
    akash_token = login_data["token"]
    assert login_data["user"]["staff_id"] == "ABHL341"
    assert login_data["user"]["role"] == "staff"

    # Supervisor logs in
    super_login = client.post(
        "/api/auth/login",
        json={"staff_id": "SUPER01", "password": "admin123"},
    )
    assert super_login.status_code == 200
    super_token = super_login.get_json()["token"]

    # =========================================================================
    # Step 2: Upload real roster image (or synthetic fixture if file missing)
    # =========================================================================
    if ROSTER_IMAGE_PATH.exists():
        file_bytes = ROSTER_IMAGE_PATH.read_bytes()
    else:
        file_bytes = b"fake-roster-image-bytes"

    import io
    upload_file = (io.BytesIO(file_bytes), "roster.jpeg")

    upload_res = client.post(
        "/api/roster/upload",
        data={
            "roster_file": upload_file,
            "month": "2026-09",
            "infer_sections": "true",
        },
        content_type="multipart/form-data",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert upload_res.status_code == 201
    upload_data = upload_res.get_json()
    assert upload_data["success"] is True
    schedule_id = upload_data["schedule_id"]
    job_id = upload_data["job_id"]
    assert upload_data["staff_id"] == "ABHL341"
    assert upload_data["month"] == "2026-09"
    # Invariant: Draft schedule is NOT approved
    assert upload_data["is_approved"] is False

    # Check job progress
    job_res = client.get(
        f"/api/roster/status/{job_id}",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert job_res.status_code == 200
    assert job_res.get_json()["job"]["status"] == "completed"

    # =========================================================================
    # Step 3 & 4: Retrieve draft schedule for review
    # =========================================================================
    draft_res = client.get(
        f"/api/schedule/draft/{schedule_id}",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert draft_res.status_code == 200
    draft_data = draft_res.get_json()["draft"]
    assert draft_data["is_approved"] is False
    assert draft_data["approved_at"] is None

    recon = draft_data["reconstruction"]
    entries = recon["entries"]
    assert len(entries) == 30

    # =========================================================================
    # Step 5: Verify safety invariants in extracted schedule
    # =========================================================================
    # Safety invariant: Confusable ID check (ABHL341 vs ABH1341) flagged for review
    assert recon.get("matched_staff_id") == "ABH1341"
    assert "confusion" in recon.get("match_reason", "").lower() or "confusable" in recon.get("match_reason", "").lower()
    assert recon.get("status") == "needs_review"

    assert any("not found" in w.lower() or "confusable" in w.lower() for w in recon.get("warnings", []))


    # Section 1 evening shift inferred via cyclic pattern E -> N -> M (supported by Sec 2 Night & Sec 3 Morning)
    day_1 = next(e for e in entries if e["day_number"] == 1)
    assert day_1["normalized_shift"] in ("E", "Evening")
    assert day_1.get("source_type") == "section_inference"
    status_1 = day_1.get("review_status") or day_1.get("status")
    assert status_1 in ("inferred_needs_confirmation", "needs_review")

    # Safety invariant: Day 29 raw token is "A" -> NEVER converted into M/E/N
    day_29 = next(e for e in entries if e["day_number"] == 29)
    assert day_29["raw_value"] == "A"
    assert day_29["normalized_shift"] is None
    status_29 = day_29.get("review_status") or day_29.get("status")
    assert status_29 in ("needs_review", "missing")

    # Block 2 night shift recovered by section inference with explicit provenance
    day_11 = next(e for e in entries if e["day_number"] == 11)
    assert day_11["normalized_shift"] in ("N", "Night")
    assert day_11.get("source_type") == "section_inference"
    assert day_11.get("review_status") == "inferred_needs_confirmation"



    # =========================================================================
    # Step 6: User reviews and confirms entries (e.g. Day 5 Saturday OFF)
    # =========================================================================
    review_res = client.post(
        "/api/schedule/review",
        json={
            "schedule_id": schedule_id,
            "day_number": 5,
            "shift": "OFF",
            "notes": "Verified Saturday off duty",
        },
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert review_res.status_code == 200

    # Verify cell is updated in SQLite
    updated_draft = get_draft_schedule(schedule_id)
    day_5 = next(e for e in updated_draft["reconstruction"]["entries"] if e["day_number"] == 5)
    assert day_5["normalized_shift"] == "OFF"
    assert day_5["review_status"] == "confirmed"

    # =========================================================================
    # Step 7: Draft remains INACTIVE until authorized approval
    # =========================================================================
    # Regular staff cannot approve schedule (403 Forbidden)
    staff_approve_res = client.post(
        "/api/schedule/approve",
        json={"schedule_id": schedule_id},
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert staff_approve_res.status_code == 403

    # Active calendar endpoint returns 404 since schedule is not yet approved
    cal_unapproved_res = client.get(
        "/api/schedule/approved/ABHL341/2026-09",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert cal_unapproved_res.status_code == 404
    assert cal_unapproved_res.get_json()["is_approved"] is False

    # Authorized supervisor approves schedule
    super_approve_res = client.post(
        "/api/schedule/approve",
        json={"schedule_id": schedule_id},
        headers={"Authorization": f"Bearer {super_token}"},
    )
    assert super_approve_res.status_code == 200
    assert super_approve_res.get_json()["is_approved"] is True

    # =========================================================================
    # Step 8: Approved schedule is retrieved and displayed in calendar
    # =========================================================================
    cal_approved_res = client.get(
        "/api/schedule/approved/ABHL341/2026-09",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert cal_approved_res.status_code == 200
    cal_data = cal_approved_res.get_json()
    assert cal_data["success"] is True
    assert cal_data["schedule"]["is_approved"] is True
    assert cal_data["schedule"]["approved_by"] == "SUPER01"
    assert len(cal_data["schedule"]["reconstruction"]["entries"]) == 30
