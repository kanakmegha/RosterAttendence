"""
Unit tests for RosterTableReconstructor in Medical Staff Schedule Tracker.

Validates 2D table reconstruction, date-to-cell mapping, staff ID confusion handling,
concatenated token normalization, and cell status invariants using synthetic fixtures.
"""

import pytest
from table_reconstructor import RosterTableReconstructor, are_ids_confusable, normalize_id


@pytest.fixture
def synthetic_september_roster_words():
    """
    Synthetic word fixture representing a 2D roster table for September 2026 (30 days, starts on Tuesday).
    Header:
      - Title: "SEPTEMBER:-2026" at y=10
      - Day Numbers: Anchors 10, 15, 20, 25, 30 at y=50
    Columns:
      - S.no: x=10..30
      - ID: x=35..80
      - Name: x=100..180
      - Off Day: x=210..240
      - Floor: x=250..295
      - Days 1..30: x=320 to 1100 (step = ~26 px)
    """
    words = [
        # Title
        {"text": "SEPTEMBER:-2026", "x": 500, "y": 10, "width": 120, "height": 15, "cx": 560, "cy": 17},
        # Metadata headers
        {"text": "S.no", "x": 10, "y": 50, "width": 20, "height": 10, "cx": 20, "cy": 55},
        {"text": "ID", "x": 40, "y": 50, "width": 15, "height": 10, "cx": 47, "cy": 55},
        {"text": "NAME", "x": 110, "y": 50, "width": 30, "height": 10, "cx": 125, "cy": 55},
        {"text": "OFF DAY", "x": 210, "y": 50, "width": 30, "height": 10, "cx": 225, "cy": 55},
        {"text": "FLOOR", "x": 260, "y": 50, "width": 30, "height": 10, "cx": 275, "cy": 55},
        # Day anchors in header: Day 10 at 554, Day 20 at 814, Day 30 at 1074 (step = 26px)
        {"text": "10", "x": 545, "y": 50, "width": 18, "height": 10, "cx": 554, "cy": 55},
        {"text": "15", "x": 675, "y": 50, "width": 18, "height": 10, "cx": 684, "cy": 55},
        {"text": "20", "x": 805, "y": 50, "width": 18, "height": 10, "cx": 814, "cy": 55},
        {"text": "25", "x": 935, "y": 50, "width": 18, "height": 10, "cx": 944, "cy": 55},
        {"text": "30", "x": 1065, "y": 50, "width": 18, "height": 10, "cx": 1074, "cy": 55},
    ]

    # Employee 1: ABHL248 (Nand Kishore, Floor: 9TH A WING, Off: WED)
    # Day 1: x = 554 - 9 * 26 = 320
    # Day 5 (Saturday): x = 320 + 4*26 = 424 -> OFF
    # Day 10: x = 554 -> M
    # Day 20: x = 814 -> N
    # Day 30: x = 1074 -> E
    words.extend([
        {"text": "1", "x": 10, "y": 80, "width": 10, "height": 12, "cx": 15, "cy": 86},
        {"text": "ABHL248", "x": 35, "y": 80, "width": 45, "height": 12, "cx": 57, "cy": 86},
        {"text": "Nand", "x": 100, "y": 80, "width": 25, "height": 12, "cx": 112, "cy": 86},
        {"text": "Kishore", "x": 130, "y": 80, "width": 35, "height": 12, "cx": 147, "cy": 86},
        {"text": "WED", "x": 210, "y": 80, "width": 22, "height": 12, "cx": 221, "cy": 86},
        {"text": "9TH A WING", "x": 250, "y": 80, "width": 45, "height": 12, "cx": 272, "cy": 86},
        {"text": "OFF", "x": 418, "y": 80, "width": 16, "height": 12, "cx": 426, "cy": 86},  # Day 5
        {"text": "M", "x": 548, "y": 80, "width": 12, "height": 12, "cx": 554, "cy": 86},    # Day 10
        {"text": "N", "x": 808, "y": 80, "width": 12, "height": 12, "cx": 814, "cy": 86},    # Day 20
        {"text": "E", "x": 1068, "y": 80, "width": 12, "height": 12, "cx": 1074, "cy": 86},  # Day 30
    ])

    # Employee 2: Akash with OCR visual confusion ABH1341 instead of ABHL341
    # Row at y=110
    words.extend([
        {"text": "2", "x": 10, "y": 110, "width": 10, "height": 12, "cx": 15, "cy": 116},
        {"text": "ABH1341", "x": 35, "y": 110, "width": 45, "height": 12, "cx": 57, "cy": 116},
        {"text": "AKASH", "x": 110, "y": 110, "width": 35, "height": 12, "cx": 127, "cy": 116},
        {"text": "SAT", "x": 210, "y": 110, "width": 22, "height": 12, "cx": 221, "cy": 116},
        {"text": "4TH FLOOR", "x": 250, "y": 110, "width": 45, "height": 12, "cx": 272, "cy": 116},
        {"text": "OFFI", "x": 418, "y": 110, "width": 18, "height": 12, "cx": 427, "cy": 116},  # Day 5 (border artifact)
        {"text": "NN", "x": 678, "y": 110, "width": 14, "height": 12, "cx": 685, "cy": 116},    # Day 15 (doubled token)
        {"text": "M", "x": 808, "y": 110, "width": 12, "height": 12, "cx": 814, "cy": 116},     # Day 20
    ])

    return words


# 1. Exact Staff ID matching and cell mapping
def test_exact_id_matching(synthetic_september_roster_words):
    recon = RosterTableReconstructor(
        words=synthetic_september_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL248"
    ).reconstruct()

    assert recon["status"] == "confirmed"
    assert recon["matched_staff_id"] == "ABHL248"
    assert recon["staff_name"] == "Nand Kishore"
    assert recon["floor_department"] == "9TH A WING"
    assert recon["weekly_off_day"] == "WED"
    assert len(recon["entries"]) == 30

    # Day 5 (Saturday) should be OFF
    day5 = next(e for e in recon["entries"] if e["day_number"] == 5)
    assert day5["normalized_shift"] == "Off"
    assert day5["status"] == "confirmed"

    # Day 10 should be Morning
    day10 = next(e for e in recon["entries"] if e["day_number"] == 10)
    assert day10["normalized_shift"] == "Morning"
    assert day10["status"] == "confirmed"

    # Day 20 should be Night
    day20 = next(e for e in recon["entries"] if e["day_number"] == 20)
    assert day20["normalized_shift"] == "Night"
    assert day20["status"] == "confirmed"

    # Day 30 should be Evening
    day30 = next(e for e in recon["entries"] if e["day_number"] == 30)
    assert day30["normalized_shift"] == "Evening"
    assert day30["status"] == "confirmed"


# 2. Similar-looking Staff ID (L vs 1) is flagged for review rather than silently accepted
def test_similar_id_confusion_flagged(synthetic_september_roster_words):
    recon = RosterTableReconstructor(
        words=synthetic_september_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL341"  # OCR read as ABH1341
    ).reconstruct()

    # INVARIANT: Must NOT be confirmed silently. Must require review.
    assert recon["status"] == "needs_review"
    assert recon["matched_staff_id"] == "ABH1341"
    assert recon["staff_name"] == "AKASH"
    assert any("confusion between" in w for w in recon["warnings"])

    # Cell mapping is still preserved for review
    day5 = next(e for e in recon["entries"] if e["day_number"] == 5)
    assert day5["normalized_shift"] == "Off"


# 3. Concatenated OCR tokens are de-concatenated and normalized
def test_concatenated_tokens_normalized(synthetic_september_roster_words):
    recon = RosterTableReconstructor(
        words=synthetic_september_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL341"
    ).reconstruct()

    # Day 5 has 'OFFI' (border artifact) -> should normalize to 'Off' with needs_review
    day5 = next(e for e in recon["entries"] if e["day_number"] == 5)
    assert day5["raw_value"] == "OFFI"
    assert day5["normalized_shift"] == "Off"
    assert day5["status"] == "needs_review"

    # Day 15 has 'NN' -> should normalize to 'Night'
    day15 = next(e for e in recon["entries"] if e["day_number"] == 15)
    assert day15["raw_value"] == "NN"
    assert day15["normalized_shift"] == "Night"


# 4. Invariant: Blank/missing cells are NEVER inferred as Off
def test_blank_cells_remain_missing_never_off(synthetic_september_roster_words):
    recon = RosterTableReconstructor(
        words=synthetic_september_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL248"
    ).reconstruct()

    # Day 1 had no observation
    day1 = next(e for e in recon["entries"] if e["day_number"] == 1)
    assert day1["status"] == "missing"
    assert day1["raw_value"] is None
    assert day1["normalized_shift"] is None

    # INVARIANT: No missing cell may ever have normalized_shift == 'Off'
    for entry in recon["entries"]:
        if entry["status"] == "missing":
            assert entry["normalized_shift"] != "Off"


# 5. ID not found returns clean structured error without crashing or fabricating schedule
def test_id_not_found(synthetic_september_roster_words):
    recon = RosterTableReconstructor(
        words=synthetic_september_roster_words,
        month_str="2026-09",
        requested_staff_id="NONEXISTENT_999"
    ).reconstruct()

    assert recon["status"] == "id_not_found"
    assert recon["matched_staff_id"] is None
    assert len(recon["entries"]) == 0
    assert any("not found" in w for w in recon["warnings"])


# 6. Confusable ID checker unit test
def test_are_ids_confusable_rules():
    assert are_ids_confusable("ABHL341", "ABHL341") == (True, "exact")
    # L vs 1
    is_conf, _ = are_ids_confusable("ABHL341", "ABH1341")
    assert is_conf is True
    # O vs 0
    is_conf, _ = are_ids_confusable("NURSE01", "NURSEO1")
    assert is_conf is True
    # Different numbers should NOT match
    is_conf, _ = are_ids_confusable("ABHL341", "ABHL342")
    assert is_conf is False
