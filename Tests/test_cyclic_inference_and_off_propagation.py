"""
Comprehensive Automated Test Suite for:
1. Optimised Roster Shift-Pattern Inference (Cyclic Sequence: E -> N -> M)
2. OFF-Day Propagation as a Higher-Priority Rule
3. Exact Calendar Boundaries across 28, 29, 30, and 31-day months (including Days 10, 11, 20, 21, 30, 31)
"""

from datetime import date
import pytest

from section_inference import (
    InferenceSourceType,
    InferenceStatus,
    detect_roster_sections,
    infer_section_shifts,
    solve_cyclic_shift_pattern,
    resolve_and_propagate_off_days,
)


def make_schedule_fixture(month_str: str, num_days: int, day_shifts: dict) -> dict:
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
            "provenance": f"OCR x=100, y=200" if norm else None,
            "review_reason": None,
        })

    return {
        "roster_month": month_str,
        "requested_staff_id": "TEST01",
        "matched_staff_id": "TEST01",
        "match_reason": "exact",
        "staff_name": "Test Nurse",
        "status": "confirmed",
        "entries": entries,
        "warnings": [],
    }


# =============================================================================
# 1. Section and Month Boundaries Tests
# =============================================================================

@pytest.mark.parametrize("year,month,length,expected_sec3_end", [
    (2025, 2, 28, 28),   # 28-day February
    (2024, 2, 29, 29),   # 29-day Leap-year February
    (2026, 9, 30, 30),   # 30-day September
    (2026, 10, 31, 31),  # 31-day October
])
def test_section_and_month_boundaries_exact(year, month, length, expected_sec3_end):
    """Verifies that months are divided into exactly 3 sections: 1-10, 11-20, 21-last day."""
    sections, warnings = detect_roster_sections(year=year, month=month, month_length=length)
    assert len(sections) == 3

    # Section 1: 1–10
    assert sections[0]["start_day"] == 1
    assert sections[0]["end_day"] == 10
    assert sections[0]["day_count"] == 10

    # Section 2: 11–20
    assert sections[1]["start_day"] == 11
    assert sections[1]["end_day"] == 20
    assert sections[1]["day_count"] == 10

    # Section 3: 21–last day
    assert sections[2]["start_day"] == 21
    assert sections[2]["end_day"] == expected_sec3_end
    assert sections[2]["day_count"] == expected_sec3_end - 21 + 1


def test_boundary_days_coverage_and_uniqueness():
    """Explicitly tests days 10, 11, 20, 21, 30, and 31. Ensures every date appears exactly once."""
    for length in (28, 29, 30, 31):
        sections, _ = detect_roster_sections(year=2026, month=1, month_length=length)
        covered_days = []
        for s in sections:
            covered_days.extend(range(s["start_day"], s["end_day"] + 1))

        # Check coverage from 1 to length
        assert covered_days == list(range(1, length + 1))
        # Ensure no duplicates
        assert len(covered_days) == len(set(covered_days))

        # Check boundary days specifically
        assert 10 in range(sections[0]["start_day"], sections[0]["end_day"] + 1)
        assert 11 in range(sections[1]["start_day"], sections[1]["end_day"] + 1)
        assert 20 in range(sections[1]["start_day"], sections[1]["end_day"] + 1)
        assert 21 in range(sections[2]["start_day"], sections[2]["end_day"] + 1)
        if length >= 30:
            assert 30 in range(sections[2]["start_day"], sections[2]["end_day"] + 1)
        if length == 31:
            assert 31 in range(sections[2]["start_day"], sections[2]["end_day"] + 1)


# =============================================================================
# 2. Shift Pattern Inference (Cyclic Sequence E -> N -> M)
# =============================================================================

def test_cyclic_inference_from_day19_night_and_day21_morning():
    """
    Required Example:
    Day 19 reliably identified as N and Day 21 reliably identified as M:
    - Section 1 (Days 1–10): Evening (E)
    - Section 2 (Days 11–20): Night (N)
    - Section 3 (Days 21–last): Morning (M)
    Including Day 31 in a 31-day month!
    """
    shifts = {
        19: ("N", "Night", "observed", 0.95),
        21: ("M", "Morning", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-10", 31, shifts)
    inferred = infer_section_shifts(recon)

    secs = inferred["section_inference"]["sections"]
    assert len(secs) == 3
    assert secs[0]["proposed_shift"] == "Evening"
    assert secs[1]["proposed_shift"] == "Night"
    assert secs[2]["proposed_shift"] == "Morning"

    # Verify Day 1 through 10 inferred as Evening
    entries = {e["day_number"]: e for e in inferred["entries"]}
    for d in range(1, 11):
        assert entries[d]["normalized_shift"] == "Evening"
        assert entries[d]["source_type"] == InferenceSourceType.SECTION_INFERENCE
        assert entries[d]["status"] == InferenceStatus.INFERRED_NEEDS_CONFIRMATION

    # Verify Day 11 through 20 inferred as Night (except Day 19 which was observed)
    for d in range(11, 21):
        assert entries[d]["normalized_shift"] == "Night"
        if d == 19:
            assert entries[d]["source_type"] == InferenceSourceType.OCR_OBSERVED
        else:
            assert entries[d]["source_type"] == InferenceSourceType.SECTION_INFERENCE

    # Verify Day 21 through 31 inferred as Morning (including Day 31!)
    for d in range(21, 32):
        assert entries[d]["normalized_shift"] == "Morning"
        if d == 21:
            assert entries[d]["source_type"] == InferenceSourceType.OCR_OBSERVED
        else:
            assert entries[d]["source_type"] == InferenceSourceType.SECTION_INFERENCE

    # Day 31 specifically verified under third-section pattern
    assert entries[31]["normalized_shift"] == "Morning"
    assert entries[31]["section_start_day"] == 21
    assert entries[31]["section_end_day"] == 31


def test_cyclic_inference_preserves_deviating_reliable_observation():
    """Rule 4: An explicitly observed reliable shift is NEVER overwritten merely because it differs from pattern."""
    shifts = {
        2: ("E", "Evening", "observed", 0.95),  # Established Section 1 Evening observation
        5: ("N", "Night", "observed", 0.95),    # Swap/deviating shift in Section 1
        15: ("N", "Night", "observed", 0.95),   # Section 2 Night
        25: ("M", "Morning", "observed", 0.95), # Section 3 Morning
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}
    # Reliable observations are preserved and contradictions flagged
    assert entries[5]["raw_value"] == "N"
    assert entries[5]["normalized_shift"] == "Night"
    assert entries[5]["source_type"] == InferenceSourceType.OCR_OBSERVED
    assert entries[5]["status"] == InferenceStatus.NEEDS_REVIEW
    assert "conflicts" in entries[5]["review_reason"] or "differs" in entries[5]["review_reason"]

    assert entries[2]["normalized_shift"] == "Evening"
    # Unobserved Day 1 is not falsely extrapolated on conflicting observations
    assert entries[1]["normalized_shift"] is None


def test_contradictory_inter_section_observations_flagged():
    """Duplicate shifts across sections do not follow the 3-distinct-shift pattern, preventing false inference."""
    shifts = {
        5: ("Evening", "Evening", "observed", 0.95),
        15: ("Evening", "Evening", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    secs = inferred["section_inference"]["sections"]
    # Cannot establish 3-shift pattern when duplicate shifts are observed
    assert secs[2]["proposed_shift"] is None
    assert any("duplicate shifts" in w.lower() or "conflict" in w.lower() for w in inferred["warnings"])


def test_unsupported_sections_left_unresolved():
    """If only 1 section has observations, unsupported sections remain unresolved."""
    shifts = {
        15: ("N", "Night", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    secs = inferred["section_inference"]["sections"]
    assert secs[0]["proposed_shift"] is None  # Sec 1 unsupported
    assert secs[1]["proposed_shift"] == "Night"  # Sec 2 direct
    assert secs[2]["proposed_shift"] is None  # Sec 3 unsupported

    entries = {e["day_number"]: e for e in inferred["entries"]}
    assert entries[1]["normalized_shift"] is None
    assert entries[30]["normalized_shift"] is None


def test_inference_is_deterministic():
    """Verifies that running inference multiple times on identical input produces identical outputs."""
    shifts = {
        15: ("N", "Night", "observed", 0.95),
        25: ("M", "Morning", "observed", 0.95),
    }
    recon1 = make_schedule_fixture("2026-09", 30, shifts)
    recon2 = make_schedule_fixture("2026-09", 30, shifts)

    res1 = infer_section_shifts(recon1)
    res2 = infer_section_shifts(recon2)

    assert res1["entries"] == res2["entries"]
    assert res1["section_inference"] == res2["section_inference"]


# =============================================================================
# 3. OFF-Day Propagation as a Higher-Priority Rule
# =============================================================================

@pytest.mark.parametrize("off_day", [10, 11, 20, 21, 30, 31])
def test_off_propagation_at_boundaries_and_day_31(off_day):
    """
    OFF observed on boundary days (10, 11, 20, 21, 30, 31):
    - Overrides expected section shift.
    - Locks the date as OFF.
    - Does NOT propagate to adjacent dates!
    """
    shifts = {
        15: ("N", "Night", "observed", 0.95),
        25: ("M", "Morning", "observed", 0.95),
        off_day: ("OFF", "Off", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-10", 31, shifts)
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}
    # The off_day MUST be Off
    assert entries[off_day]["normalized_shift"] == "Off"
    assert entries[off_day]["source_type"] == InferenceSourceType.OCR_OBSERVED

    # Adjacent days must NOT be converted to Off!
    if off_day > 1:
        prev_day = off_day - 1
        assert entries[prev_day]["normalized_shift"] != "Off"
    if off_day < 31:
        next_day = off_day + 1
        assert entries[next_day]["normalized_shift"] != "Off"


def test_off_propagation_overrides_inferred_shift():
    """OFF propagation overrides an inferred shift for that date, and subsequent inference cannot touch it."""
    # Section 1 is inferred as Evening (via Sec 2=Night, Sec 3=Morning)
    # Day 5 has OFF
    shifts = {
        5: ("OFF", "Off", "observed", 0.95),
        15: ("N", "Night", "observed", 0.95),
        25: ("M", "Morning", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}
    # Days 4 and 6 are inferred Evening
    assert entries[4]["normalized_shift"] == "Evening"
    assert entries[6]["normalized_shift"] == "Evening"

    # Day 5 is strictly Off, NEVER Evening!
    assert entries[5]["normalized_shift"] == "Off"


def test_ambiguous_low_confidence_off_does_not_override():
    """Rule 8: Low-confidence or ambiguous OCR reading does NOT automatically override as confirmed OFF."""
    shifts = {
        5: ("O", None, "needs_review", 0.40),  # Ambiguous low-confidence token
        15: ("N", "Night", "observed", 0.95),
        25: ("M", "Morning", "observed", 0.95),
    }
    recon = make_schedule_fixture("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    entries = {e["day_number"]: e for e in inferred["entries"]}
    # Day 5 is NOT locked as Off because confidence is 0.40
    assert entries[5]["normalized_shift"] != "Off"
