"""
Comprehensive Automated Test Suite for:
1. Roster Pattern Inference (Contiguous shift sections, NO cyclic rotation)
2. Recurring weekly OFF day inference and propagation (higher priority than shift inference)
3. Calendar boundaries across 28-, 29-, 30-, and 31-day months (including Day 1, 10, 11, 15, 19, 20, 21, 22, 30, 31)
4. Non-target shift 'A' handling (valid observation, blank final recorded shift, provenance preserved)
5. Precedence: OFF overrides inferred shifts; conflicting observations produce review flags.
"""

import calendar
from datetime import date
from pathlib import Path
import pytest

from section_inference import (
    InferenceSourceType,
    InferenceStatus,
    detect_and_propagate_recurring_weekly_off,
    detect_roster_sections,
    infer_contiguous_shift_sections,
    infer_section_shifts,
)
from table_reconstructor import RosterTableReconstructor


def make_schedule_fixture(month_str: str, num_days: int, day_shifts: dict, weekly_off_hint: str = None) -> dict:
    """
    Helper to create synthetic schedule fixtures.
    day_shifts format: {day_num: (raw_val, norm_shift, status, confidence)}
    """
    parts = month_str.split("-")
    year, month = int(parts[0]), int(parts[1])

    entries = []
    for d in range(1, num_days + 1):
        dt = date(year, month, d)
        if d in day_shifts:
            raw, norm, st, conf = day_shifts[d]
        else:
            raw, norm, st, conf = None, None, "missing", None

        entries.append({
            "date": dt.isoformat(),
            "day_number": d,
            "weekday": dt.strftime("%A"),
            "raw_value": raw,
            "normalized_shift": norm,
            "status": st,
            "confidence": conf,
            "provenance": f"OCR x=100, y=200" if norm or raw else None,
            "review_reason": None,
        })

    return {
        "roster_month": month_str,
        "requested_staff_id": "TEST01",
        "matched_staff_id": "TEST01",
        "match_reason": "exact",
        "staff_name": "Test Nurse",
        "weekly_off_day": weekly_off_hint,
        "status": "confirmed",
        "entries": entries,
        "warnings": [],
    }


# =============================================================================
# Requirement 1 & 7: 28-, 29-, 30-, 31-day months & Calendar Weekday Calculations
# =============================================================================

@pytest.mark.parametrize("year,month,length,leap_status", [
    (2025, 2, 28, "non-leap February"),
    (2024, 2, 29, "leap-year February"),
    (2026, 9, 30, "standard 30-day month"),
    (2026, 10, 31, "standard 31-day month"),
])
def test_month_length_and_weekday_calculations(year, month, length, leap_status):
    """Calculates actual number of days in selected month, including leap years, with exact weekdays."""
    calc_length = calendar.monthrange(year, month)[1]
    assert calc_length == length, f"Month length mismatch for {year}-{month} ({leap_status})"

    recon = make_schedule_fixture(f"{year:04d}-{month:02d}", length, {})
    inferred = infer_section_shifts(recon)

    entries = inferred["entries"]
    assert len(entries) == length

    for idx, e in enumerate(entries, start=1):
        assert e["day_number"] == idx
        dt = date(year, month, idx)
        assert e["date"] == dt.isoformat()
        assert e["weekday"] == dt.strftime("%A")


# =============================================================================
# Requirement 2 & 11: 31-day month, N extending 22–31, Day 31 never omitted
# =============================================================================

def test_31_day_month_night_extends_22_through_31():
    """
    If Day 31 is reliably N and continuous N section extends backwards, infer N for days 22–31.
    Day 21 is NOT automatically assigned N unless evidence supports that range.
    Day 31 is never omitted.
    """
    # October 2026 (31 days):
    # Day 22 observed Night, Day 31 observed Night
    # Day 21 is blank/transition without evidence supporting N
    shifts = {
        22: ("N", "Night", "observed", 0.95),
        31: ("N", "Night", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-10", 31, shifts)
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}

    # All dates 1 to 31 exist exactly once
    assert len(entries) == 31
    assert 31 in entries

    # Day 31 is Night
    assert entries[31]["normalized_shift"] == "Night"
    assert entries[31]["source_type"] == InferenceSourceType.OCR_OBSERVED

    # Days 22 through 30 receive Night
    for d in range(22, 31):
        assert entries[d]["normalized_shift"] == "Night"

    # Day 21 is NOT automatically assigned Night because N only extends backwards to Day 22
    assert entries[21]["normalized_shift"] != "Night"


# =============================================================================
# Requirement 3: M range from Day 10 through Day 15 when supported
# =============================================================================

def test_morning_range_from_day_10_through_day_15():
    """
    If day 15 is reliably M and day 21 marks the beginning of a reliable N section,
    use the observed transition and neighbouring evidence to establish the M range.
    If the roster confirms that M runs from day 10 through day 15, infer M for missing cells in that range.
    """
    # September 2026 (30 days):
    # Day 10 observed Morning, Day 15 observed Morning
    # Day 21 observed Night (starts N section)
    shifts = {
        10: ("M", "Morning", "observed", 0.95),
        15: ("M", "Morning", "observed", 0.95),
        21: ("N", "Night", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}

    # Range 10 through 15 confirmed as Morning
    for d in range(10, 16):
        assert entries[d]["normalized_shift"] == "Morning", f"Day {d} expected Morning"
        if d in (10, 15):
            assert entries[d]["source_type"] == InferenceSourceType.OCR_OBSERVED
        else:
            assert entries[d]["source_type"] == InferenceSourceType.SECTION_INFERENCE


# =============================================================================
# Requirement 4: Inferring E for remaining dates only when pattern supports it
# =============================================================================

def test_infer_evening_for_remaining_section_when_pattern_supports():
    """
    Once M and N ranges are reliably established in two sections of a 3-section roster,
    infer E for the remaining eligible dates.
    """
    # Section 2 has Night, Section 3 has Morning
    # Section 1 has only Day 5 = OFF
    shifts = {
        5: ("OFF", "Off", "observed", 0.95),
        15: ("N", "Night", "observed", 0.95),
        25: ("M", "Morning", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    secs = inferred["section_inference"]["sections"]
    assert secs[0]["proposed_shift"] == "Evening"
    assert secs[1]["proposed_shift"] == "Night"
    assert secs[2]["proposed_shift"] == "Morning"

    entries = {e["day_number"]: e for e in inferred["entries"]}
    # Section 1 eligible dates (e.g. Day 1) receive Evening
    assert entries[1]["normalized_shift"] == "Evening"
    assert entries[1]["source_type"] == InferenceSourceType.SECTION_INFERENCE


def test_do_not_infer_evening_when_pattern_does_not_support():
    """
    If only ONE shift is observed across the roster (e.g. only Night),
    do NOT invent E for remaining sections without pattern support.
    """
    shifts = {
        15: ("N", "Night", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    secs = inferred["section_inference"]["sections"]
    assert secs[0]["proposed_shift"] is None
    assert secs[2]["proposed_shift"] is None

    entries = {e["day_number"]: e for e in inferred["entries"]}
    assert entries[1]["normalized_shift"] is None
    assert entries[25]["normalized_shift"] is None


# =============================================================================
# Requirement 5: Valid 'A' observation leaves final recorded shift blank
# =============================================================================

def test_valid_a_observation_results_in_blank_final_shift():
    """
    A and other non-target shifts: recognise as valid observations when possible,
    but leave their final recorded shift field blank (normalized_shift = None).
    Preserve raw OCR observations and provenance internally.
    Do not treat blank final shift field as OCR failure.
    """
    shifts = {
        22: ("M", "Morning", "observed", 0.95),
        29: ("A", None, "needs_review", 0.70),  # Valid non-target observation
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}
    day29 = entries[29]

    # Invariant: raw token preserved
    assert day29["raw_value"] == "A"
    # Invariant: final recorded shift field left blank
    assert day29["normalized_shift"] is None
    # Invariant: source type is OCR observed
    assert day29["source_type"] == InferenceSourceType.OCR_OBSERVED
    # Invariant: Not missing, has valid provenance and explanation
    assert day29["status"] == InferenceStatus.NEEDS_REVIEW
    assert "non-target shift 'A'" in day29["review_reason"].lower() or "unrecognized shift code 'a'" in day29["review_reason"].lower()

    # Day 22 was Morning and Day 25 was inferred Morning, but Day 29 was NEVER overwritten!
    assert entries[25]["normalized_shift"] == "Morning"
    assert entries[29]["normalized_shift"] is None


# =============================================================================
# Requirement 6 & 8: Saturday OFF propagation & OFF overriding inferred shifts
# =============================================================================

def test_saturday_off_propagates_to_every_saturday_and_overrides_inferred_shifts():
    """
    If a confidently identified OFF day is a Saturday and roster follows a weekly Saturday-off schedule:
    - Mark every Saturday in that month as OFF, including blank dates.
    - Propagate OFF only to the matching weekday.
    - Do NOT propagate to neighbouring weekdays (Friday, Sunday).
    - OFF rule is higher priority than inferred shifts.
    """
    # September 2026:
    # Saturdays are: Sept 5, Sept 12, Sept 19, Sept 26
    # Day 5 has confirmed OFF, staff weekly off hint is "SAT"
    # Section 2 (11..20) has Night observed
    shifts = {
        5: ("OFF", "Off", "observed", 0.95),
        15: ("N", "Night", "observed", 0.95),
        25: ("M", "Morning", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts, weekly_off_hint="SAT")
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}

    # All Saturdays must be Off
    for sat in (5, 12, 19, 26):
        assert entries[sat]["normalized_shift"] == "Off", f"Day {sat} (Saturday) must be Off"
        if sat == 5:
            assert entries[sat]["source_type"] == InferenceSourceType.OCR_OBSERVED
        else:
            assert entries[sat]["source_type"] == InferenceSourceType.OFF_PROPAGATION

    # Crucial Invariant: Day 12 and Day 19 fall in Section 2 (which is inferred Night).
    # OFF must OVERRIDE inferred Night!
    assert entries[12]["normalized_shift"] == "Off"
    assert entries[19]["normalized_shift"] == "Off"

    # Crucial Invariant: Never propagate to neighbouring weekdays!
    # Fridays (Sept 4, 11, 18, 25) must NOT be Off
    for fri in (4, 11, 18, 25):
        assert entries[fri]["normalized_shift"] != "Off", f"Friday Day {fri} must not be Off"
    # Sundays (Sept 6, 13, 20, 27) must NOT be Off
    for sun in (6, 13, 20, 27):
        assert entries[sun]["normalized_shift"] != "Off", f"Sunday Day {sun} must not be Off"


# =============================================================================
# Requirement 9: Conflicting reliable observations producing review flags
# =============================================================================

def test_working_shift_on_weekly_off_day_produces_conflict_review_flag():
    """
    If a reliable working shift contradicts the proposed weekly OFF pattern,
    flag the conflict instead of silently overwriting the observation.
    """
    # Staff weekly off is Saturday, Day 5 is OFF
    # But Day 12 (Saturday) has a reliable Morning shift observation!
    shifts = {
        5: ("OFF", "Off", "observed", 0.95),
        12: ("M", "Morning", "observed", 0.95),  # Reliable observation on Saturday!
        15: ("N", "Night", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts, weekly_off_hint="SAT")
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}
    day12 = entries[12]

    # Invariant: Observation preserved! Not silently overwritten with Off!
    assert day12["normalized_shift"] == "Morning"
    assert day12["source_type"] == InferenceSourceType.OCR_OBSERVED
    # Invariant: Flagged for review
    assert day12["status"] == InferenceStatus.NEEDS_REVIEW
    assert "conflict" in day12["review_reason"].lower()
    assert any("conflict" in w.lower() for w in inferred["warnings"])


def test_intra_section_conflicting_observations_abort_inference():
    """Conflicting observed working shifts in one section abort inference and flag review."""
    shifts = {
        12: ("M", "Morning", "observed", 0.95),
        17: ("N", "Night", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    sec2 = inferred["section_inference"]["sections"][1]
    assert sec2["has_conflict"] is True
    assert sec2["proposed_shift"] is None

    entries = {e["day_number"]: e for e in inferred["entries"]}
    # Both observed shifts are preserved and flagged
    assert entries[12]["status"] == InferenceStatus.NEEDS_REVIEW
    assert entries[17]["status"] == InferenceStatus.NEEDS_REVIEW
    # Unobserved days remain missing/unresolved
    assert entries[14]["normalized_shift"] is None


# =============================================================================
# Requirement 10: Ambiguous observations not silently treated as certain
# =============================================================================

def test_ambiguous_reading_not_silently_treated_as_certain():
    """Ambiguous or low confidence tokens do not silently establish section patterns."""
    shifts = {
        15: ("NN", "Night", "needs_review", 0.50),  # Ambiguous low confidence reading
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    sec2 = inferred["section_inference"]["sections"][1]
    # Invariant: Ambiguous reading with low confidence does not become proposed pattern
    assert sec2["proposed_shift"] is None

    entries = {e["day_number"]: e for e in inferred["entries"]}
    assert entries[14]["normalized_shift"] is None


# =============================================================================
# Requirement 11: All dates appearing exactly once, including Day 31
# =============================================================================

@pytest.mark.parametrize("length", [28, 29, 30, 31])
def test_all_dates_appear_exactly_once(length):
    """Verifies that every date from 1 to month_length appears exactly once in sequential order."""
    month_str = f"2026-0{length % 9 + 1}"
    recon = make_schedule_fixture(month_str, length, {})
    inferred = infer_section_shifts(recon)

    entries = inferred["entries"]
    assert len(entries) == length
    day_numbers = [e["day_number"] for e in entries]
    assert day_numbers == list(range(1, length + 1))
    assert len(set(day_numbers)) == length
    if length == 31:
        assert 31 in day_numbers


# =============================================================================
# Requirement 12: Existing OCR endpoint & frontend response compatibility
# =============================================================================

def test_frontend_response_compatibility():
    """Verifies that reconstruction JSON maintains full schema compatibility for Android and web."""
    recon = make_schedule_fixture("2026-09", 30, {15: ("N", "Night", "observed", 0.95), 25: ("M", "Morning", "observed", 0.95)})
    inferred = infer_section_shifts(recon)

    # Required top-level keys
    for key in ("roster_month", "staff_name", "matched_staff_id", "status", "entries", "section_inference", "warnings"):
        assert key in inferred

    # Required section_inference schema
    sec_inf = inferred["section_inference"]
    for key in ("rule", "sections", "total_eligible_dates", "total_inferred_dates", "total_unresolved_dates"):
        assert key in sec_inf

    # Required section item schema
    for sec in sec_inf["sections"]:
        for key in ("section_index", "start_day", "end_day", "day_count", "proposed_shift", "inferred_dates", "unresolved_dates"):
            assert key in sec
