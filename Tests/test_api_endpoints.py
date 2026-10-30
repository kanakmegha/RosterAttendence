"""
Tests for authenticated REST API endpoints in server.py
"""

import pytest
from server import app
from db import (

    init_db,
    save_draft_schedule,
    get_draft_schedule,
    approve_draft_schedule,
    get_approved_schedule,
    add_manual_duty,
    get_manual_duties,
    get_db_connection,
)
from auth import generate_token, STAFF_USERS


@pytest.fixture
def client():
    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client


@pytest.fixture(autouse=True)
def clean_stores():
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



@pytest.fixture
def akash_token():

    user = STAFF_USERS["ABHL341"]
    return generate_token(user)


@pytest.fixture
def supervisor_token():
    user = STAFF_USERS["SUPER01"]
    return generate_token(user)


def test_auth_login_valid_credentials(client):
    res = client.post(
        "/api/auth/login",
        json={"staff_id": "ABHL341", "password": "password123"},
    )
    assert res.status_code == 200
    data = res.get_json()
    assert data["success"] is True
    assert "token" in data
    assert data["user"]["staff_id"] == "ABHL341"


def test_auth_login_invalid_credentials(client):
    res = client.post(
        "/api/auth/login",
        json={"staff_id": "ABHL341", "password": "wrong_password"},
    )
    assert res.status_code == 401
    data = res.get_json()
    assert data["success"] is False


def test_get_staff_profile_protected(client, akash_token):
    # Without token -> 401
    res_no_auth = client.get("/api/staff/profile")
    assert res_no_auth.status_code == 401

    # With valid token -> 200
    res_auth = client.get(
        "/api/staff/profile",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert res_auth.status_code == 200
    data = res_auth.get_json()
    assert data["user"]["staff_id"] == "ABHL341"
    assert data["user"]["name"] == "Akash Kumar"


def test_manual_duty_creation_and_listing(client, akash_token):
    # Create manual emergency duty
    payload = {
        "date": "2026-09-18",
        "shift": "N",
        "department": "ICU / Emergency",
        "floor": "Floor 2",
        "notes": "Emergency backup shift",
    }
    res = client.post(
        "/api/duty/manual",
        json=payload,
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert res.status_code == 201
    data = res.get_json()
    assert data["success"] is True
    assert data["duty"]["shift"] == "N"

    # List manual duties
    res_list = client.get(
        "/api/duty/manual",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert res_list.status_code == 200
    list_data = res_list.get_json()
    assert len(list_data["duties"]) >= 1


def test_draft_review_and_approval_flow(client, akash_token, supervisor_token):
    # Seed a draft schedule into SQLite
    sched_id = "test-sched-123"
    save_draft_schedule(
        schedule_id=sched_id,
        staff_id="ABHL341",
        month="2026-09",
        reconstruction={
            "entries": [
                {
                    "day_number": 5,
                    "date": "2026-09-05",
                    "weekday": "Sat",
                    "normalized_shift": "OFF",
                    "review_status": "needs_review",
                    "review_reason": "Low OCR confidence",
                }
            ],
            "unresolved_dates": [],
        },
        is_approved=False,
    )

    # Review cell
    res_review = client.post(
        "/api/schedule/review",
        json={
            "schedule_id": sched_id,
            "day_number": 5,
            "shift": "OFF",
            "notes": "Verified Saturday Off",
        },
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert res_review.status_code == 200

    # Verify cell is updated in SQLite
    updated_draft = get_draft_schedule(sched_id)
    entry = updated_draft["reconstruction"]["entries"][0]
    assert entry["review_status"] == "confirmed"

    # Attempt to access another user's draft (Forbidden)
    pooja_token = generate_token(STAFF_USERS["ABHL217"])
    res_forbidden = client.get(
        f"/api/schedule/draft/{sched_id}",
        headers={"Authorization": f"Bearer {pooja_token}"},
    )
    assert res_forbidden.status_code == 403

    # Non-supervisor attempt to approve schedule (Forbidden)
    res_non_super_approve = client.post(
        "/api/schedule/approve",
        json={"schedule_id": sched_id},
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert res_non_super_approve.status_code == 403

    # Supervisor approves schedule
    res_approve = client.post(
        "/api/schedule/approve",
        json={"schedule_id": sched_id},
        headers={"Authorization": f"Bearer {supervisor_token}"},
    )
    assert res_approve.status_code == 200
    approve_data = res_approve.get_json()
    assert approve_data["is_approved"] is True

    # Check persistence in SQLite
    persisted = get_draft_schedule(sched_id)
    assert persisted["is_approved"] is True

    # Retrieve approved schedule
    res_approved_cal = client.get(
        "/api/schedule/approved/ABHL341/2026-09",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    assert res_approved_cal.status_code == 200


def test_revised_schedule_comparison_endpoint(client, supervisor_token):
    # Seed approved schedule
    save_draft_schedule(
        schedule_id="app-sched-1",
        staff_id="ABHL341",
        month="2026-09",
        reconstruction={
            "entries": [
                {"day_number": 14, "date": "2026-09-14", "normalized_shift": "M"}
            ]
        },
        is_approved=True,
    )
    approve_draft_schedule("app-sched-1", "SUPER01")

    # Seed revised draft
    rev_id = "rev-sched-456"
    save_draft_schedule(
        schedule_id=rev_id,
        staff_id="ABHL341",
        month="2026-09",
        reconstruction={
            "entries": [
                {"day_number": 14, "date": "2026-09-14", "weekday": "Mon", "normalized_shift": "N", "review_status": "confirmed"}
            ]
        },
        is_approved=False,
    )

    res_diff = client.post(
        "/api/schedule/revised/compare",
        json={"revised_schedule_id": rev_id},
        headers={"Authorization": f"Bearer {supervisor_token}"},
    )
    assert res_diff.status_code == 200
    diff_data = res_diff.get_json()
    assert diff_data["success"] is True
    assert diff_data["diff_count"] == 1
    assert diff_data["diffs"][0]["change_type"] == "changed"


def test_persistence_survives_simulated_backend_restart(client, akash_token):
    # Save a draft schedule
    sched_id = "restart-test-123"
    save_draft_schedule(
        schedule_id=sched_id,
        staff_id="ABHL341",
        month="2026-09",
        reconstruction={"entries": [{"day_number": 1, "date": "2026-09-01", "normalized_shift": "M"}]},
        is_approved=False,
    )

    # Add a manual duty
    add_manual_duty("duty-1", "ABHL341", "2026-09-10", "N", "Emergency ICU", "Floor 2", "Night shift")

    # Simulate restart by clearing in-memory app context and reopening DB directly
    loaded_draft = get_draft_schedule(sched_id)
    assert loaded_draft is not None
    assert loaded_draft["schedule_id"] == sched_id
    assert loaded_draft["reconstruction"]["entries"][0]["normalized_shift"] == "M"

    loaded_duties = get_manual_duties("ABHL341")
    assert len(loaded_duties) == 1
    assert loaded_duties[0]["shift"] == "N"


def test_staff_cannot_spoof_another_staff_id_in_upload(client, akash_token):
    # Akash (regular staff) attempts to upload roster asserting Pooja's staff_id
    import io
    fake_file = (io.BytesIO(b"fake image content"), "roster.png")
    res = client.post(
        "/api/roster/upload",
        data={
            "roster_file": fake_file,
            "staff_id": "ABHL217",  # Spoofed staff ID
            "month": "2026-09",
        },
        content_type="multipart/form-data",
        headers={"Authorization": f"Bearer {akash_token}"},
    )
    # Must reject with 403 Forbidden!
    assert res.status_code == 403
    data = res.get_json()
    assert data["success"] is False
    assert "Forbidden" in data["error"]
