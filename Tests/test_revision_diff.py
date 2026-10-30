"""
Tests for Domain Models and RevisionDiff ported from Swift DomainModelTests.swift
"""

import pytest
from domain_models import (
    ScheduleEntry,
    ManualDuty,
    ExtractedCell,
    DraftSchedule,
    EntrySourceType,
    ReviewStatus,
)
from roster_month import RosterMonth
from revision_diff import compute_schedule_revision_diff, ChangeType


def test_manual_duty_converts_to_schedule_entry():
    duty = ManualDuty(
        date="2026-09-15",
        department="Emergency ICU",
        shift="N",
        floor="Floor 3",
        notes="Urgent coverage",
    )
    entry = duty.to_schedule_entry()
    assert entry.date == "2026-09-15"
    assert entry.shift_code == "N"
    assert entry.department == "Emergency ICU"
    assert entry.floor == "Floor 3"
    assert entry.source_type == EntrySourceType.MANUAL_EMERGENCY
    assert entry.review_status == ReviewStatus.CONFIRMED
    assert entry.notes == "Urgent coverage"


def test_draft_schedule_is_unapproved_by_default():
    month = RosterMonth.from_string("2026-09")
    draft = DraftSchedule(
        staff_id="ABHL341",
        month=month,
        entries=[],
    )
    assert draft.is_approved is False
    assert draft.approved_at is None

    # Approval transition
    draft.approve()
    assert draft.is_approved is True
    assert draft.approved_at is not None


def test_revision_diff_distinguishes_unresolved_from_removed():
    # Previous approved entry has Morning shift on Sept 14
    curr_14 = ScheduleEntry(
        date="2026-09-14",
        display_label="Morning",
        shift_code="M",
    )
    # Previous approved entry has Night shift on Sept 15
    curr_15 = ScheduleEntry(
        date="2026-09-15",
        display_label="Night",
        shift_code="N",
    )

    # Revised roster:
    # Day 14 is missing/unresolved OCR token -> MUST be UNRESOLVED, NEVER assumed REMOVED!
    rev_14 = ExtractedCell(
        day_number=14,
        date="2026-09-14",
        weekday="Mon",
        review_status=ReviewStatus.MISSING,
        normalized_shift=None,
    )
    # Day 15 is explicitly confirmed OFF in revised roster -> REMOVED_CONFIRMED
    rev_15 = ExtractedCell(
        day_number=15,
        date="2026-09-15",
        weekday="Tue",
        raw_value="OFF",
        normalized_shift="OFF",
        review_status=ReviewStatus.CONFIRMED,
    )

    diffs = compute_schedule_revision_diff([curr_14, curr_15], [rev_14, rev_15])
    diff_map = {d.date: d for d in diffs}

    assert diff_map["2026-09-14"].change_type == ChangeType.UNRESOLVED
    assert "Cannot assume removal" in diff_map["2026-09-14"].explanation

    assert diff_map["2026-09-15"].change_type == ChangeType.REMOVED_CONFIRMED


def test_revision_diff_detects_manual_emergency_conflict():
    emergency_duty = ScheduleEntry(
        date="2026-09-20",
        display_label="Night",
        shift_code="N",
        source_type=EntrySourceType.MANUAL_EMERGENCY,
    )
    # Revised roster proposes Morning
    rev_20 = ExtractedCell(
        day_number=20,
        date="2026-09-20",
        weekday="Sun",
        normalized_shift="M",
        review_status=ReviewStatus.CONFIRMED,
    )

    diffs = compute_schedule_revision_diff([emergency_duty], [rev_20])
    assert len(diffs) == 1
    assert diffs[0].change_type == ChangeType.CONFLICT
    assert "conflicts with revised" in diffs[0].explanation
