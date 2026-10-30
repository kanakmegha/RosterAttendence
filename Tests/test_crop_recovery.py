"""
Regression Test Suite: Crop-Based Shift Recovery
Tests programmatic cropping, multi-pipeline preprocessing comparison,
multi-provider OCR evaluation, Sept 14-15 NN separation, pixel density analysis,
and strict cell status categorization.
"""

import json
from pathlib import Path
from PIL import Image
import pytest

from cell_crop_recovery import (
    CellCropRecoverer,
    CellStatus,
    apply_crop_recovery_to_reconstruction,
)
from table_reconstructor import RosterTableReconstructor


ROSTER_IMAGE_PATH = Path("/Users/skanakmegha/Downloads/roster.jpeg")
CACHE_OCR_PATH = Path(
    "/Users/skanakmegha/.gemini/antigravity-ide/brain/b7496765-c5cc-4573-b241-99708491753a/scratch/roster_ocr_cache.json"
)


@pytest.fixture
def real_roster_words():
    if not CACHE_OCR_PATH.exists():
        pytest.skip(f"OCR cache not found at {CACHE_OCR_PATH}")
    with open(CACHE_OCR_PATH) as f:
        data = json.load(f)
    return data["words"]


# 1. Programmatic cell cropping using geometric boundaries
def test_programmatic_cell_cropping():
    if not ROSTER_IMAGE_PATH.exists():
        pytest.skip(f"Roster image not found at {ROSTER_IMAGE_PATH}")

    recoverer = CellCropRecoverer(str(ROSTER_IMAGE_PATH))

    # Crop Day 5 for Akash (x: 423.5 - 451.2, y: 289 - 305)
    crop_img, cbox = recoverer.crop_cell(423.5, 289.0, 451.2, 305.0)

    assert crop_img is not None
    assert crop_img.width > 20
    assert crop_img.height > 10
    assert cbox["left"] in (422, 423)
    assert cbox["right"] in (451, 452)
    assert cbox["top"] == 288
    assert cbox["bottom"] == 306


# 2. Preprocessing pipeline comparison: binarization vs contrast vs upscale
def test_preprocessing_pipeline_comparison(tmp_path):
    if not ROSTER_IMAGE_PATH.exists():
        pytest.skip(f"Roster image not found at {ROSTER_IMAGE_PATH}")

    recoverer = CellCropRecoverer(str(ROSTER_IMAGE_PATH), cache_dir=str(tmp_path))
    crop_img, _ = recoverer.crop_cell(423.5, 289.0, 451.2, 305.0)

    variants = recoverer.generate_preprocessed_variants(crop_img, "test_day_05")

    assert "raw" in variants
    assert "upscale" in variants
    assert "contrast" in variants
    assert "binarized" in variants

    for method, path in variants.items():
        assert path.exists(), f"Variant {method} file was not generated"

    # Verify dimensions
    raw_im = Image.open(variants["raw"])
    up_im = Image.open(variants["upscale"])
    # 4x upscale + 20px border on each side = (raw_w * 4) + 40
    assert up_im.width == (raw_im.width * 4) + 40
    assert up_im.height == (raw_im.height * 4) + 40

    # Vision OCR comparison on Day 5 crop (if local binary available on macOS):
    if not recoverer.vision_binary_path.exists():
        pytest.skip("Apple Vision binary not available (non-macOS environment)")

    batch_res = recoverer.run_apple_vision_batch(list(variants.values()))
    raw_res = batch_res.get(str(variants["raw"].resolve()), {})
    up_res = batch_res.get(str(variants["upscale"].resolve()), {})
    contrast_res = batch_res.get(str(variants["contrast"].resolve()), {})


    assert up_res.get("text") == "OFF"
    assert contrast_res.get("text") == "OFF"


# 3. Multi-provider OCR comparison: Apple Vision local vs OCR.space behavior
def test_multi_provider_ocr_evaluation(tmp_path):
    if not ROSTER_IMAGE_PATH.exists():
        pytest.skip(f"Roster image not found at {ROSTER_IMAGE_PATH}")

    recoverer = CellCropRecoverer(str(ROSTER_IMAGE_PATH), cache_dir=str(tmp_path))
    crop_img, _ = recoverer.crop_cell(423.5, 289.0, 451.2, 305.0)
    variants = recoverer.generate_preprocessed_variants(crop_img, "test_provider")

    if recoverer.vision_binary_path.exists():
        # Local Apple Vision recognizes isolated upscaled crop if available
        vis_res = recoverer.run_apple_vision_batch([variants["contrast"].resolve()])
        vis_item = vis_res.get(str(variants["contrast"].resolve()), {})
        assert vis_item.get("text") == "OFF"
        assert vis_item.get("confidence", 0) > 0.8


    # OCR.space documentation: small isolated character crops lack document structure
    # When tested without key or with small crop, records proper failure/note rather than crashing
    ocr_text, ocr_conf, ocr_meta = recoverer.run_ocr_space_attempt(variants["contrast"])
    assert "provider" in ocr_meta
    assert ocr_meta["provider"] == "ocr_space"


# 4. September 14–15 separate recognition and evidence-based unresolved status
def test_september_14_15_separate_crops(real_roster_words, tmp_path):
    if not ROSTER_IMAGE_PATH.exists():
        pytest.skip(f"Roster image not found at {ROSTER_IMAGE_PATH}")

    recon = RosterTableReconstructor(
        words=real_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL341",
    ).reconstruct()

    recoverer = CellCropRecoverer(str(ROSTER_IMAGE_PATH), cache_dir=str(tmp_path))
    results = recoverer.recover_employee_shifts(recon, row_y=297.0)

    day14 = next(r for r in results if r.day_number == 14)
    day15 = next(r for r in results if r.day_number == 15)

    # Invariant: Neither day is arbitrarily assigned or dropped
    assert day14.final_status == CellStatus.NEEDS_REVIEW
    assert day15.final_status == CellStatus.NEEDS_REVIEW
    assert any("merged 'MONTUE' column header" in r for r in day14.review_reasons)
    assert any("merged 'MONTUE' column header" in r for r in day15.review_reasons)


# 5. Cell status classification: observed, needs_review, unreadable, missing, confirmed_blank
def test_cell_status_classification_rules(real_roster_words, tmp_path):
    if not ROSTER_IMAGE_PATH.exists():
        pytest.skip(f"Roster image not found at {ROSTER_IMAGE_PATH}")

    recon = RosterTableReconstructor(
        words=real_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL341",
    ).reconstruct()

    updated = apply_crop_recovery_to_reconstruction(
        reconstruction=recon,
        image_path=str(ROSTER_IMAGE_PATH),
        target_row_y=297.0,
        cache_dir=str(tmp_path),
    )

    entries = updated["entries"]
    assert len(entries) == 30

    # Invariant: Statuses must strictly belong to the allowed set
    allowed_statuses = {
        CellStatus.OBSERVED,
        CellStatus.NEEDS_REVIEW,
        CellStatus.UNREADABLE,
        CellStatus.MISSING,
        CellStatus.CONFIRMED_BLANK,
    }
    for e in entries:
        assert e["status"] in allowed_statuses

    # Invariant: No cell should claim confirmed_blank unless verified
    confirmed_blanks = [e for e in entries if e["status"] == CellStatus.CONFIRMED_BLANK]
    assert len(confirmed_blanks) == 0

    # Invariant: Shaded / unrecovered cells are marked unreadable, never guessed
    unreadable_entries = [e for e in entries if e["status"] == CellStatus.UNREADABLE]
    assert len(unreadable_entries) > 0
    for u in unreadable_entries:
        assert u["normalized_shift"] is None
        assert "unreadable" in (u.get("review_reason") or "").lower() or "visual marks" in (u.get("review_reason") or "").lower()


# 6. Staff ID and Legend Safety Invariants
def test_staff_id_and_legend_safety_invariants(real_roster_words, tmp_path):
    if not ROSTER_IMAGE_PATH.exists():
        pytest.skip(f"Roster image not found at {ROSTER_IMAGE_PATH}")

    recon = RosterTableReconstructor(
        words=real_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL341",  # Scanned as ABH1341
    ).reconstruct()

    updated = apply_crop_recovery_to_reconstruction(
        reconstruction=recon,
        image_path=str(ROSTER_IMAGE_PATH),
        target_row_y=297.0,
        cache_dir=str(tmp_path),
    )

    # Invariant 1: Staff ID confusion flags all entries for review
    assert updated["matched_staff_id"] == "ABH1341"
    assert updated["status"] == "needs_review"

    # Invariant 2: Shift 'A' on Day 29 remains unresolved (not in standard legend)
    day29 = next(e for e in updated["entries"] if e["day_number"] == 29)
    assert day29["raw_value"] == "A"
    assert day29["normalized_shift"] is None
    assert day29["status"] == CellStatus.NEEDS_REVIEW
    assert "not in configured legend" in day29["review_reason"].lower()


# 7. Provenance, pixel metrics, and attempt logging
def test_provenance_and_attempt_logging(real_roster_words, tmp_path):
    if not ROSTER_IMAGE_PATH.exists():
        pytest.skip(f"Roster image not found at {ROSTER_IMAGE_PATH}")

    recon = RosterTableReconstructor(
        words=real_roster_words,
        month_str="2026-09",
        requested_staff_id="ABHL341",
    ).reconstruct()

    updated = apply_crop_recovery_to_reconstruction(
        reconstruction=recon,
        image_path=str(ROSTER_IMAGE_PATH),
        target_row_y=297.0,
        cache_dir=str(tmp_path),
    )

    for entry in updated["entries"]:
        assert "crop_coords" in entry
        assert "left" in entry["crop_coords"]
        assert "top" in entry["crop_coords"]

        assert "pixel_analysis" in entry
        assert "dark_pixel_ratio" in entry["pixel_analysis"]
        assert "mean_gray" in entry["pixel_analysis"]

        assert "crop_attempts" in entry
        assert len(entry["crop_attempts"]) >= 4  # raw, upscale, contrast, binarized
        methods = {a["preprocessing_method"] for a in entry["crop_attempts"]}
        assert {"raw", "upscale", "contrast", "binarized"}.issubset(methods)
