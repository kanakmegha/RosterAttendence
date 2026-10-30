"""
Unit and Regression Test Suite for Section-Based Shift Inference
Validates all 12 required test cases for 10-day roster blocks, month variations,
strict safety rules, conflict handling, and provenance tracking.
"""

import json
from pathlib import Path
import pytest

from section_inference import (
    InferenceSourceType,
    InferenceStatus,
    detect_roster_sections,
    export_schedule_to_excel,
    infer_section_shifts,
)
from table_reconstructor import RosterTableReconstructor


def make_dummy_reconstruction(month_str: str, num_days: int, day_shifts: dict) -> dict:
    """
    Helper to create synthetic schedule reconstructions for testing.
    day_shifts format: {day_num: (raw_val, norm_shift, status)}
    """
    parts = month_str.split("-")
    year, month = int(parts[0]), int(parts[1])

    entries = []
    for d in range(1, num_days + 1):
        from datetime import date
        dt = date(year, month, d)
        if d in day_shifts:
            raw, norm, st = day_shifts[d]
        else:
            raw, norm, st = None, None, "missing"

        entries.append({
            "date": dt.isoformat(),
            "day_number": d,
            "weekday": dt.strftime("%A"),
            "raw_value": raw,
            "normalized_shift": norm,
            "status": st,
            "confidence": 0.95 if norm else None,
            "provenance": f"OCR x=100, y=200" if norm else None,
            "review_reason": None,
        })

    return {
        "roster_month": month_str,
        "requested_staff_id": "ABHL100",
        "matched_staff_id": "ABHL100",
        "match_reason": "exact",
        "staff_name": "Nurse Test",
        "status": "confirmed",
        "entries": entries,
        "warnings": [],
    }


# Test Case 1: A 30-day month divided into three 10-day sections
def test_case_01_30_day_month_three_10_day_sections():
    sections, warnings = detect_roster_sections(year=2026, month=9, month_length=30)
    assert len(sections) == 3
    assert sections[0]["start_day"] == 1 and sections[0]["end_day"] == 10 and sections[0]["day_count"] == 10
    assert sections[1]["start_day"] == 11 and sections[1]["end_day"] == 20 and sections[1]["day_count"] == 10
    assert sections[2]["start_day"] == 21 and sections[2]["end_day"] == 30 and sections[2]["day_count"] == 10
    assert len(warnings) == 0


# Test Case 2: A 31-day month with a separately detected day 31
def test_case_02_31_day_month_separate_day_31():
    sections, warnings = detect_roster_sections(
        year=2026,
        month=10,
        month_length=31,
        layout_hints={"day_31_mode": "separate"},
    )
    assert len(sections) == 4
    assert sections[0]["start_day"] == 1 and sections[0]["end_day"] == 10
    assert sections[1]["start_day"] == 11 and sections[1]["end_day"] == 20
    assert sections[2]["start_day"] == 21 and sections[2]["end_day"] == 30
    assert sections[3]["start_day"] == 31 and sections[3]["end_day"] == 31 and sections[3]["day_count"] == 1
    assert sections[3]["is_standalone_day_31"] is True


# Test Case 3: A 31-day month where the final section contains days 21–31
def test_case_03_31_day_month_extended_section_21_31():
    sections, warnings = detect_roster_sections(
        year=2026,
        month=10,
        month_length=31,
        layout_hints={"day_31_mode": "extended"},
    )
    assert len(sections) == 3
    assert sections[0]["start_day"] == 1 and sections[0]["end_day"] == 10
    assert sections[1]["start_day"] == 11 and sections[1]["end_day"] == 20
    assert sections[2]["start_day"] == 21 and sections[2]["end_day"] == 31 and sections[2]["day_count"] == 11


# Test Case 4: A 28-day February
def test_case_04_28_day_february():
    sections, warnings = detect_roster_sections(year=2025, month=2, month_length=28)
    assert len(sections) == 3
    assert sections[0]["start_day"] == 1 and sections[0]["end_day"] == 10 and sections[0]["day_count"] == 10
    assert sections[1]["start_day"] == 11 and sections[1]["end_day"] == 20 and sections[1]["day_count"] == 10
    assert sections[2]["start_day"] == 21 and sections[2]["end_day"] == 28 and sections[2]["day_count"] == 8


# Test Case 5: A 29-day leap-year February
def test_case_05_29_day_leap_year_february():
    sections, warnings = detect_roster_sections(year=2024, month=2, month_length=29)
    assert len(sections) == 3
    assert sections[0]["start_day"] == 1 and sections[0]["end_day"] == 10 and sections[0]["day_count"] == 10
    assert sections[1]["start_day"] == 11 and sections[1]["end_day"] == 20 and sections[1]["day_count"] == 10
    assert sections[2]["start_day"] == 21 and sections[2]["end_day"] == 29 and sections[2]["day_count"] == 9


# Test Case 6: One observed N with otherwise missing cells in a verified all-N section
def test_case_06_one_observed_night_with_otherwise_missing_cells():
    # Only Day 17 has observed Night, other days in 11..20 are missing
    shifts = {
        17: ("N", "Night", "observed"),
    }
    recon = make_dummy_reconstruction("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    sec2 = inferred["section_inference"]["sections"][1]
    assert sec2["proposed_shift"] == "Night"
    assert sec2["inferred_dates"] == [11, 12, 13, 14, 15, 16, 18, 19, 20]

    # Check Day 12 receives inferred Night with inferred_needs_confirmation
    day12 = next(e for e in inferred["entries"] if e["day_number"] == 12)
    assert day12["normalized_shift"] == "Night"
    assert day12["status"] == InferenceStatus.INFERRED_NEEDS_CONFIRMATION
    assert day12["source_type"] == InferenceSourceType.SECTION_INFERENCE
    assert day12["supporting_dates"] == [17]

    # Check Day 17 remains ocr_observed
    day17 = next(e for e in inferred["entries"] if e["day_number"] == 17)
    assert day17["source_type"] == InferenceSourceType.OCR_OBSERVED
    assert day17["normalized_shift"] == "Night"
    assert day17["status"] == "observed"


# Test Case 7: Conflicting observed shifts in one section
def test_case_07_conflicting_observed_shifts_aborts_inference():
    # Day 12 has Morning, Day 17 has Night in Section 2 (11..20)
    shifts = {
        12: ("M", "Morning", "observed"),
        17: ("N", "Night", "observed"),
    }
    recon = make_dummy_reconstruction("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    sec2 = inferred["section_inference"]["sections"][1]
    # INVARIANT: Must not extrapolate on conflict!
    assert sec2["has_conflict"] is True
    assert sec2["proposed_shift"] is None
    assert sec2["inferred_dates"] == []
    assert any("Conflicting observed shifts" in r for r in sec2["review_reasons"])

    # Unobserved days in Section 2 must remain missing/unresolved
    day15 = next(e for e in inferred["entries"] if e["day_number"] == 15)
    assert day15["normalized_shift"] is None
    assert day15["status"] == "missing"


# Test Case 8: An unknown A token in an otherwise consistent section
def test_case_08_unknown_token_a_preserved_never_converted():
    # Section 3 (21..30): Day 22 has Morning, Day 29 has 'A', other days missing
    shifts = {
        22: ("M", "Morning", "observed"),
        29: ("A", None, "needs_review"),
    }
    recon = make_dummy_reconstruction("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    sec3 = inferred["section_inference"]["sections"][2]
    assert sec3["proposed_shift"] == "Morning"
    assert any("unrecognized shift code 'A'" in r for r in sec3["review_reasons"])

    # INVARIANT: Day 29 with 'A' MUST NOT be converted to Morning!
    day29 = next(e for e in inferred["entries"] if e["day_number"] == 29)
    assert day29["raw_value"] == "A"
    assert day29["normalized_shift"] is None
    assert day29["status"] == "needs_review"
    assert day29["source_type"] == InferenceSourceType.OCR_OBSERVED

    # Other missing days (e.g. Day 25) receive Morning inference candidate
    day25 = next(e for e in inferred["entries"] if e["day_number"] == 25)
    assert day25["normalized_shift"] == "Morning"
    assert day25["status"] == InferenceStatus.INFERRED_NEEDS_CONFIRMATION


# Test Case 9: An explicit OFF within a section is never overwritten and never inferred
def test_case_09_explicit_off_preserved_never_overwritten_never_inferred():
    # Section 2 (11..20): Day 12 is OFF, Day 14 is Night, Day 19 is OFF
    shifts = {
        12: ("OFF", "Off", "observed"),
        14: ("N", "Night", "observed"),
        19: ("OFF", "Off", "observed"),
    }
    recon = make_dummy_reconstruction("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    sec2 = inferred["section_inference"]["sections"][1]
    assert sec2["proposed_shift"] == "Night"
    assert sec2["observed_off_dates"] == [12, 19]

    # INVARIANT 1: Days 12 and 19 remain Off and are NEVER overwritten by Night
    day12 = next(e for e in inferred["entries"] if e["day_number"] == 12)
    day19 = next(e for e in inferred["entries"] if e["day_number"] == 19)
    assert day12["normalized_shift"] == "Off"
    assert day12["source_type"] == InferenceSourceType.OCR_OBSERVED
    assert day19["normalized_shift"] == "Off"
    assert day19["source_type"] == InferenceSourceType.OCR_OBSERVED

    # INVARIANT 2: Missing day 15 receives Night, NEVER Off
    day15 = next(e for e in inferred["entries"] if e["day_number"] == 15)
    assert day15["normalized_shift"] == "Night"


# Test Case 10: A section with no reliable observed shift
def test_case_10_section_with_no_reliable_observed_shift():
    # Section 1 has only OFF on Day 5, no working shifts (M, E, N)
    shifts = {
        5: ("OFF", "Off", "observed"),
    }
    recon = make_dummy_reconstruction("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    sec1 = inferred["section_inference"]["sections"][0]
    # INVARIANT: Must not invent shift when no working shift observed
    assert sec1["proposed_shift"] is None
    assert sec1["inferred_dates"] == []
    assert any("No reliable working shift observed" in r for r in sec1["review_reasons"])

    # Unobserved days remain unresolved
    day1 = next(e for e in inferred["entries"] if e["day_number"] == 1)
    assert day1["normalized_shift"] is None
    assert day1["status"] == "missing"


# Test Case 11: A revised roster whose section boundaries differ from the previous roster
def test_case_11_revised_roster_custom_boundaries():
    # Roster revised with two 15-day sections: [1..15] and [16..30]
    custom = [(1, 15), (16, 30)]
    shifts = {
        5: ("E", "Evening", "observed"),
        25: ("M", "Morning", "observed"),
    }
    recon = make_dummy_reconstruction("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon, custom_sections=custom)

    sections = inferred["section_inference"]["sections"]
    assert len(sections) == 2
    assert sections[0]["start_day"] == 1 and sections[0]["end_day"] == 15
    assert sections[0]["proposed_shift"] == "Evening"

    assert sections[1]["start_day"] == 16 and sections[1]["end_day"] == 30
    assert sections[1]["proposed_shift"] == "Morning"

    # Day 14 now falls into Section 1 and receives Evening inference
    day14 = next(e for e in inferred["entries"] if e["day_number"] == 14)
    assert day14["normalized_shift"] == "Evening"
    assert day14["section_start_day"] == 1
    assert day14["section_end_day"] == 15


# Test Case 12: Proof that inferred values retain provenance and require confirmation
def test_case_12_inferred_values_retain_provenance_and_require_confirmation(tmp_path):
    shifts = {
        14: ("N", "Night", "observed"),
    }
    recon = make_dummy_reconstruction("2026-09", 30, shifts)
    inferred = infer_section_shifts(recon)

    day15 = next(e for e in inferred["entries"] if e["day_number"] == 15)

    # Invariants for inferred cell:
    assert day15["source_type"] == InferenceSourceType.SECTION_INFERENCE
    assert day15["status"] == InferenceStatus.INFERRED_NEEDS_CONFIRMATION
    assert day15["review_status"] == InferenceStatus.INFERRED_NEEDS_CONFIRMATION
    assert day15["inference_rule"] in ("10_day_section_uniform_shift", "contiguous_shift_section_inference")
    assert day15["confidence"] == 0.85
    assert day15["section_start_day"] == 11
    assert day15["section_end_day"] == 20
    assert day15["supporting_dates"] == [14]
    assert day15["supporting_raw_tokens"] == ["N"]
    assert day15["raw_value"] is None  # Original raw value NEVER overwritten!
    assert "Inferred from" in day15["provenance"]

    # Overall schedule remains a draft
    assert inferred["status"] == "needs_review"

    # Excel export verification
    excel_path = tmp_path / "test_schedule.xlsx"
    out_file = export_schedule_to_excel(inferred, str(excel_path))
    assert Path(out_file).exists()
    assert Path(out_file).stat().st_size > 1000


# Test Case 13: Real Roster Regression Test on Akash and Pooja
def test_case_13_real_roster_section_inference_akash_and_pooja(tmp_path):
    cache_path = Path("/Users/skanakmegha/.gemini/antigravity-ide/brain/b7496765-c5cc-4573-b241-99708491753a/scratch/roster_ocr_cache.json")
    if not cache_path.exists():
        pytest.skip("roster_ocr_cache.json not found in scratch directory")

    with open(cache_path) as f:
        data = json.load(f)
    words = data["words"]

    # 1. Test Akash (ABHL341 / ABH1341) with contiguous shift section pattern inference
    recon_akash = RosterTableReconstructor(words, month_str="2026-09", requested_staff_id="ABHL341").reconstruct()
    inferred_akash = infer_section_shifts(recon_akash)

    sec_info = inferred_akash["section_inference"]
    secs = sec_info["sections"]
    assert len(secs) == 3

    # Section 1 (Days 1–10): Inferred as Evening supported by 3-shift pattern (Sec 2 Night & Sec 3 Morning)
    assert secs[0]["proposed_shift"] == "Evening"
    assert 5 in secs[0]["observed_off_dates"]
    assert len(secs[0]["inferred_dates"]) > 0
    assert 1 in secs[0]["inferred_dates"]

    # Section 1 single-shift unsupported test: when only 1 section has observations, unsupported sections remain None
    dummy_single = make_dummy_reconstruction("2026-09", 30, {15: ("N", "Night", "observed")})
    inferred_single = infer_section_shifts(dummy_single)
    secs_single = inferred_single["section_inference"]["sections"]
    assert secs_single[0]["proposed_shift"] is None
    assert secs_single[0]["inferred_dates"] == []

    # Section 2 (Days 11–20): Consistently Night -> candidate Night inferred for missing days
    assert secs[1]["proposed_shift"] == "Night"
    assert "Night" in secs[1]["observed_working_shifts"]
    assert len(secs[1]["inferred_dates"]) > 0

    # Section 3 (Days 21–30): Consistently Morning -> candidate Morning inferred, Day 29 'A' preserved unresolved
    assert secs[2]["proposed_shift"] == "Morning"
    day29 = next(e for e in inferred_akash["entries"] if e["day_number"] == 29)
    assert day29["raw_value"] == "A"
    assert day29["normalized_shift"] is None

    # Verify overall draft status preserved
    assert inferred_akash["status"] == "needs_review"

    # 2. Test Pooja (ABHL217)
    recon_pooja = RosterTableReconstructor(words, month_str="2026-09", requested_staff_id="ABHL217").reconstruct()
    inferred_pooja = infer_section_shifts(recon_pooja)
    pooja_secs = inferred_pooja["section_inference"]["sections"]

    assert pooja_secs[0]["proposed_shift"] == "Evening"
    assert pooja_secs[1]["proposed_shift"] == "Night"
    assert pooja_secs[2]["proposed_shift"] == "Morning"
