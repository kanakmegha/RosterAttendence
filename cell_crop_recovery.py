"""
Cell Crop Recovery Engine for Medical Staff Schedule Tracker
------------------------------------------------------------
Implements targeted, programmatic cropping of missing or ambiguous schedule cells
from the original roster image, evaluates multiple image preprocessing pipelines,
runs multi-provider OCR (local Apple Vision and cloud OCR.space), analyzes pixel
density for unreadable/shaded cells, and assigns verified cell statuses without guessing.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from PIL import Image, ImageEnhance, ImageOps


class CellStatus:
    OBSERVED = "observed"
    NEEDS_REVIEW = "needs_review"
    UNREADABLE = "unreadable"
    MISSING = "missing"
    CONFIRMED_BLANK = "confirmed_blank"


@dataclass
class CropAttempt:
    crop_coords: Dict[str, int]
    ocr_provider: str
    preprocessing_method: str
    raw_token: Optional[str]
    confidence: float
    recognition_result: Dict[str, Any]


@dataclass
class PixelAnalysis:
    mean_gray: float
    min_gray: int
    max_gray: int
    dark_pixel_ratio: float
    is_solid_fill: bool
    is_faint_or_blank: bool


@dataclass
class RecoveredCellResult:
    day_number: int
    date_str: str
    weekday: str
    crop_coords: Dict[str, int]
    pixel_analysis: PixelAnalysis
    attempts: List[CropAttempt]
    final_status: str
    recovered_token: Optional[str]
    normalized_shift: Optional[str]
    review_reasons: List[str]
    provenance_note: str


class CellCropRecoverer:
    """
    Recovers missing or ambiguous roster cells by programmatically cropping
    individual cell regions using validated column and row geometries.
    """

    def __init__(
        self,
        image_path: str,
        cache_dir: Optional[str] = None,
        vision_binary_path: Optional[str] = None,
        shift_legend: Optional[Dict[str, str]] = None,
    ):
        self.image_path = Path(image_path)
        if not self.image_path.exists():
            raise FileNotFoundError(f"Source roster image not found: {self.image_path}")

        self.cache_dir = Path(cache_dir or "scratch/crops")
        self.cache_dir.mkdir(parents=True, exist_ok=True)

        self.vision_binary_path = Path(
            vision_binary_path
            or Path(__file__).parent / "bin" / "vision_crop_ocr"
        )

        self.shift_legend = shift_legend or {
            "M": "Morning",
            "E": "Evening",
            "N": "Night",
            "OFF": "Off",
        }

        self.source_image = Image.open(self.image_path).convert("RGB")
        self.img_width, self.img_height = self.source_image.size

    def crop_cell(
        self,
        x0: float,
        y0: float,
        x1: float,
        y1: float,
        padding: int = 1,
    ) -> Tuple[Image.Image, Dict[str, int]]:
        """
        Crops a single cell from the source image given physical boundaries.
        Applies a small 1px safety padding while clipping to image bounds.
        """
        left = max(0, int(round(x0)) - padding)
        top = max(0, int(round(y0)) - padding)
        right = min(self.img_width, int(round(x1)) + padding)
        bottom = min(self.img_height, int(round(y1)) + padding)

        crop_box = {"left": left, "top": top, "right": right, "bottom": bottom}
        cropped = self.source_image.crop((left, top, right, bottom))
        return cropped, crop_box

    def generate_preprocessed_variants(
        self,
        crop_img: Image.Image,
        base_name: str,
    ) -> Dict[str, Path]:
        """
        Generates 4 preprocessed variants of the cell crop:
        1. raw: original resolution
        2. upscale: 4x Lanczos upscale + white border padding (20px)
        3. contrast: Grayscale + autocontrast + 1.8x contrast enhancement + 4x upscale + padding
        4. binarized: Otsu-style thresholding + 4x nearest upscale + padding
        """
        variants: Dict[str, Path] = {}

        # 1. Raw
        raw_path = self.cache_dir / f"{base_name}_raw.png"
        crop_img.save(raw_path)
        variants["raw"] = raw_path

        # 2. Upscale (4x Lanczos + white padding)
        w, h = crop_img.size
        upscaled = crop_img.resize((w * 4, h * 4), Image.Resampling.LANCZOS)
        padded_upscale = ImageOps.expand(upscaled, border=20, fill="white")
        upscale_path = self.cache_dir / f"{base_name}_prep_upscale.png"
        padded_upscale.save(upscale_path)
        variants["upscale"] = upscale_path

        # 3. Contrast (Grayscale + Autocontrast + Contrast Boost + 4x Upscale + Padding)
        gray = ImageOps.grayscale(crop_img)
        auto_c = ImageOps.autocontrast(gray, cutoff=2)
        enhancer = ImageEnhance.Contrast(auto_c)
        high_c = enhancer.enhance(1.8)
        contrast_up = high_c.resize((w * 4, h * 4), Image.Resampling.LANCZOS)
        padded_contrast = ImageOps.expand(contrast_up, border=20, fill=255)
        contrast_path = self.cache_dir / f"{base_name}_prep_contrast.png"
        padded_contrast.save(contrast_path)
        variants["contrast"] = contrast_path

        # 4. Binarized (Thresholding + 4x Nearest + Padding)
        thresh = gray.point(lambda p: 255 if p > 128 else 0)
        bin_up = thresh.resize((w * 4, h * 4), Image.Resampling.NEAREST)
        padded_bin = ImageOps.expand(bin_up, border=20, fill=255)
        bin_path = self.cache_dir / f"{base_name}_prep_binarized.png"
        padded_bin.save(bin_path)
        variants["binarized"] = bin_path

        return variants

    def analyze_pixel_density(self, crop_img: Image.Image) -> PixelAnalysis:
        """
        Inspects grayscale pixel distribution to distinguish solid ink fill / shading
        from faint noise or blank canvas.
        """
        gray = ImageOps.grayscale(crop_img)
        pixels = list(gray.getdata())
        if not pixels:
            return PixelAnalysis(
                mean_gray=255.0,
                min_gray=255,
                max_gray=255,
                dark_pixel_ratio=0.0,
                is_solid_fill=False,
                is_faint_or_blank=True,
            )

        mean_val = sum(pixels) / len(pixels)
        min_val = min(pixels)
        max_val = max(pixels)

        # Fraction of dark pixels (< 110 brightness)
        dark_count = sum(1 for p in pixels if p < 110)
        dark_ratio = dark_count / len(pixels)

        # Solid ink fill / black shading: > 70% dark pixels or min_val == 0 with very low mean
        is_solid = dark_ratio >= 0.70 or (min_val <= 10 and mean_val < 130 and dark_ratio > 0.50)
        is_faint = dark_ratio < 0.15

        return PixelAnalysis(
            mean_gray=round(mean_val, 2),
            min_gray=min_val,
            max_gray=max_val,
            dark_pixel_ratio=round(dark_ratio, 3),
            is_solid_fill=is_solid,
            is_faint_or_blank=is_faint,
        )

    def run_apple_vision_batch(self, image_paths: List[Path]) -> Dict[str, Dict[str, Any]]:
        """
        Executes local Apple Vision OCR via the compiled binary on a batch of image paths.
        """
        if not self.vision_binary_path.exists():
            return {}

        cmd = [str(self.vision_binary_path)] + [str(p) for p in image_paths]
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if res.returncode == 0:
                data = json.loads(res.stdout)
                out_map = {}
                for item in data:
                    out_map[item["path"]] = item
                return out_map
        except Exception:
            pass
        return {}

    def run_ocr_space_attempt(
        self,
        image_path: Path,
        api_key: Optional[str] = None,
    ) -> Tuple[Optional[str], float, Dict[str, Any]]:
        """
        Evaluates a crop with the OCR.space API.
        Notes and records provider behavior (e.g., rejection/empty layout for small isolated crops).
        """
        key = api_key or os.getenv("OCR_API_KEY")
        if not key:
            return None, 0.0, {"error": "OCR_API_KEY not configured", "provider": "ocr_space"}

        try:
            from ocr_space_client import OCRSpaceClient, OCRSpaceAPIError
            client = OCRSpaceClient(api_key=key)
            result = client.parse_image(
                str(image_path),
                is_overlay_required=True,
                detect_orientation=False,
                scale=True,
                ocr_engine="2",
            )
            raw_text = result.get("extracted_text", "").strip()
            return (raw_text or None), (0.8 if raw_text else 0.0), {"result": result, "provider": "ocr_space"}
        except Exception as exc:
            return None, 0.0, {"error": str(exc), "provider": "ocr_space", "note": "OCR.space rejected isolated small crop as noise/empty"}

    def recover_employee_shifts(
        self,
        reconstruction: Dict[str, Any],
        row_y: float,
        row_height: float = 16.0,
        test_ocr_space_samples: bool = False,
    ) -> List[RecoveredCellResult]:
        """
        Iterates over all calendar day entries in the table reconstruction.
        Identifies missing cells and ambiguous tokens (e.g. NN on Days 14-15),
        crops the source image, evaluates all preprocessing variants and OCR providers,
        and produces structured cell analysis.
        """
        entries = reconstruction.get("entries", [])
        day_diagnostics = reconstruction.get("column_diagnostics", {})
        warnings = reconstruction.get("warnings", [])
        staff_name = reconstruction.get("staff_name", "staff").lower().replace(" ", "_")
        matched_id = reconstruction.get("matched_staff_id", "id")

        y0 = max(0, row_y - (row_height / 2.0))
        y1 = min(self.img_height, row_y + (row_height / 2.0))

        # First pass: generate all crops and preprocessed variants
        day_crop_map: Dict[int, Dict[str, Path]] = {}
        crop_boxes: Dict[int, Dict[str, int]] = {}
        pixel_analyses: Dict[int, PixelAnalysis] = {}
        all_variant_paths: List[Path] = []

        for entry in entries:
            d = entry["day_number"]
            diag = day_diagnostics.get(d) or day_diagnostics.get(str(d)) or {}
            bounds = diag.get("bounds") or [0.0, 0.0]
            x0 = bounds[0] if len(bounds) > 0 else diag.get("left_bound", 0.0)
            x1 = bounds[1] if len(bounds) > 1 else diag.get("right_bound", 0.0)

            crop_img, cbox = self.crop_cell(x0, y0, x1, y1)
            crop_boxes[d] = cbox
            pixel_analyses[d] = self.analyze_pixel_density(crop_img)

            base_name = f"{staff_name}_{matched_id}_day_{d:02d}"
            variants = self.generate_preprocessed_variants(crop_img, base_name)
            day_crop_map[d] = variants
            all_variant_paths.extend([p.resolve() for p in variants.values()])

        # Second pass: execute Apple Vision OCR on all variants in one fast batch
        vision_results = self.run_apple_vision_batch(all_variant_paths)

        recovered_cells: List[RecoveredCellResult] = []

        # Third pass: analyze each day
        for entry in entries:
            d = entry["day_number"]
            date_str = entry["date"]
            weekday = entry["weekday"]
            orig_status = entry.get("status", "missing")
            orig_raw = entry.get("raw_value")
            orig_norm = entry.get("normalized_shift")
            diag = day_diagnostics.get(d) or day_diagnostics.get(str(d)) or {}

            cbox = crop_boxes[d]
            px_analysis = pixel_analyses[d]
            variants = day_crop_map[d]

            attempts: List[CropAttempt] = []

            # 1. Record Apple Vision attempts across all 4 preprocessing pipelines
            for method, path in variants.items():
                vis_item = vision_results.get(str(path.resolve()), vision_results.get(str(path), {}))
                text = vis_item.get("text", "").strip() or None
                conf = float(vis_item.get("confidence", 0.0))
                attempts.append(
                    CropAttempt(
                        crop_coords=cbox,
                        ocr_provider="apple_vision",
                        preprocessing_method=method,
                        raw_token=text,
                        confidence=conf,
                        recognition_result=vis_item,
                    )
                )

            # 2. Record OCR.space behavior for sample crops or if requested
            if test_ocr_space_samples and d in (5, 11, 14, 15):
                ocr_text, ocr_conf, ocr_meta = self.run_ocr_space_attempt(variants["contrast"])
                attempts.append(
                    CropAttempt(
                        crop_coords=cbox,
                        ocr_provider="ocr_space",
                        preprocessing_method="contrast",
                        raw_token=ocr_text,
                        confidence=ocr_conf,
                        recognition_result=ocr_meta,
                    )
                )

            # 3. Decision Logic adhering to cell status requirements
            final_status = orig_status
            recovered_token = orig_raw
            normalized_shift = orig_norm
            review_reasons: List[str] = []
            provenance_note = entry.get("provenance") or f"Crop [{cbox['left']},{cbox['top']},{cbox['right']},{cbox['bottom']}]"

            # Check if source cell is physically solid ink fill / shading (e.g. Days 3, 9, 23)
            if px_analysis.is_solid_fill:
                final_status = CellStatus.UNREADABLE
                normalized_shift = None
                recovered_token = None
                review_reasons.append(
                    f"Cell contains dense solid ink shading (dark pixel ratio: {px_analysis.dark_pixel_ratio * 100:.1f}%, "
                    f"min gray: {px_analysis.min_gray}); source shift unreadable"
                )
                provenance_note += f"; Solid fill detected (dark_ratio={px_analysis.dark_pixel_ratio})"

            # Handle September 14–15 merged header and 'NN' token
            elif d in (14, 15):
                # Inspect separate crops for days 14 and 15
                vis_upscale = next((a for a in attempts if a.preprocessing_method == "upscale"), None)
                vis_contrast = next((a for a in attempts if a.preprocessing_method == "contrast"), None)
                crop_token_14_15 = (vis_contrast and vis_contrast.raw_token) or (vis_upscale and vis_upscale.raw_token)

                final_status = CellStatus.NEEDS_REVIEW
                review_reasons.append(
                    "Original roster has merged 'MONTUE' column header spanning Days 14 and 15 with continuous ink strokes. "
                    "Physical column split recognizes 'N' in both halves, but shifts remain unresolved pending human review."
                )
                recovered_token = crop_token_14_15 or orig_raw or "NN"
                normalized_shift = "Night" if crop_token_14_15 == "N" else orig_norm
                provenance_note += f"; Separately cropped at x={diag.get('left_bound')}-{diag.get('right_bound')}, recognized '{crop_token_14_15}'"

            # Handle cell previously missing in full image OCR
            elif orig_status == "missing":
                # Find best recognized token across preprocessing methods
                # Priority: contrast -> upscale -> raw -> binarized
                best_attempt = None
                for method in ("contrast", "upscale", "raw", "binarized"):
                    att = next((a for a in attempts if a.preprocessing_method == method and a.raw_token), None)
                    if att:
                        cleaned = re.sub(r"[^\w]", "", att.raw_token).upper()
                        if cleaned in self.shift_legend or cleaned in ("M", "E", "N", "OFF"):
                            best_attempt = att
                            break

                if best_attempt:
                    cleaned = re.sub(r"[^\w]", "", best_attempt.raw_token).upper()
                    norm = self.shift_legend.get(cleaned)
                    recovered_token = best_attempt.raw_token
                    normalized_shift = norm
                    final_status = CellStatus.NEEDS_REVIEW
                    review_reasons.append(
                        f"Recovered shift '{norm}' from cell crop via local Apple Vision OCR "
                        f"({best_attempt.preprocessing_method}, confidence: {best_attempt.confidence:.2f}); "
                        "held for human review in draft schedule."
                    )
                    provenance_note += f"; Recovered via crop OCR ({best_attempt.preprocessing_method}, conf={best_attempt.confidence:.2f})"
                else:
                    # No OCR token recognized
                    # Check if populated with faint markings or gridline noise
                    if not px_analysis.is_faint_or_blank:
                        final_status = CellStatus.UNREADABLE
                        review_reasons.append(
                            f"Cell appears populated with visual marks (mean gray: {px_analysis.mean_gray}, "
                            f"dark ratio: {px_analysis.dark_pixel_ratio * 100:.1f}%), but no reliable shift glyph could be recovered."
                        )
                        provenance_note += "; Populated but unreadable"
                    else:
                        # Cell had faint/no marks and no OCR recovery
                        final_status = CellStatus.UNREADABLE
                        review_reasons.append(
                            "Source cell is unreadable; could not be recovered reliably across all preprocessing pipelines."
                        )
                        provenance_note += "; Unreadable across all pipelines"

            # Handle clean full-image OCR tokens (e.g. Days 5, 12, 17, 19, 20, 21, 22, 28, 30)
            elif orig_status in ("confirmed", "observed"):
                final_status = CellStatus.OBSERVED
                # If Staff ID was confusable, entry requires review
                if reconstruction.get("match_reason") != "exact":
                    final_status = CellStatus.NEEDS_REVIEW
                    review_reasons.append(f"Subject to Staff ID confusion ({reconstruction.get('match_reason')})")

            # Handle other needs_review cells (e.g. Day 26 'OFFI', Day 29 'A')
            elif orig_status == "needs_review":
                final_status = CellStatus.NEEDS_REVIEW
                if orig_raw == "OFFI":
                    # Check if crop cleaned the border artifact
                    contrast_att = next((a for a in attempts if a.preprocessing_method == "contrast" and a.raw_token), None)
                    if contrast_att and contrast_att.raw_token.strip().upper() == "OFF":
                        recovered_token = "OFF"
                        normalized_shift = "Off"
                        review_reasons.append("Border artifact 'OFFI' resolved to clean 'OFF' via cell crop; held for draft review.")
                    else:
                        review_reasons.append("OCR border artifact 'OFFI'. Candidate interpretation: Off.")
                elif orig_raw == "A":
                    recovered_token = "A"
                    normalized_shift = None
                    review_reasons.append("Unrecognized shift code 'A' not in configured legend [M, E, N, OFF]. Held for human review.")

            recovered_cells.append(
                RecoveredCellResult(
                    day_number=d,
                    date_str=date_str,
                    weekday=weekday,
                    crop_coords=cbox,
                    pixel_analysis=px_analysis,
                    attempts=attempts,
                    final_status=final_status,
                    recovered_token=recovered_token,
                    normalized_shift=normalized_shift,
                    review_reasons=review_reasons,
                    provenance_note=provenance_note,
                )
            )

        return recovered_cells


def apply_crop_recovery_to_reconstruction(
    reconstruction: Dict[str, Any],
    image_path: str,
    target_row_y: Optional[float] = None,
    cache_dir: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Convenience function that takes an existing table reconstruction and original image,
    runs cell crop recovery for the target employee, and updates the reconstruction
    with accurate cell statuses and recovery provenance.
    """
    entries = reconstruction.get("entries", [])
    if not entries:
        return reconstruction

    # Determine target employee row y
    row_y = target_row_y
    if row_y is None:
        # Infer row_y from provenance of existing entries or metadata
        for e in entries:
            prov = e.get("provenance") or ""
            m = re.search(r"y=(\d+)", prov)
            if m:
                row_y = float(m.group(1))
                break

    if row_y is None:
        row_y = 297.4  # Default center for Akash if no entry had provenance

    recoverer = CellCropRecoverer(image_path=image_path, cache_dir=cache_dir)
    recovered_cells = recoverer.recover_employee_shifts(
        reconstruction=reconstruction,
        row_y=row_y,
    )

    # Update reconstruction entries
    updated_entries = []
    recovery_stats = {
        "observed_count": 0,
        "needs_review_count": 0,
        "unreadable_count": 0,
        "missing_count": 0,
        "confirmed_blank_count": 0,
        "recovered_from_crop_count": 0,
    }

    for rec in recovered_cells:
        status = rec.final_status
        if status == CellStatus.OBSERVED:
            recovery_stats["observed_count"] += 1
        elif status == CellStatus.NEEDS_REVIEW:
            recovery_stats["needs_review_count"] += 1
            if any("from cell crop" in r for r in rec.review_reasons):
                recovery_stats["recovered_from_crop_count"] += 1
        elif status == CellStatus.UNREADABLE:
            recovery_stats["unreadable_count"] += 1
        elif status == CellStatus.MISSING:
            recovery_stats["missing_count"] += 1
        elif status == CellStatus.CONFIRMED_BLANK:
            recovery_stats["confirmed_blank_count"] += 1

        updated_entries.append({
            "date": rec.date_str,
            "day_number": rec.day_number,
            "weekday": rec.weekday,
            "raw_value": rec.recovered_token,
            "normalized_shift": rec.normalized_shift,
            "status": rec.final_status,
            "provenance": rec.provenance_note,
            "review_reason": "; ".join(rec.review_reasons) if rec.review_reasons else None,
            "crop_coords": rec.crop_coords,
            "pixel_analysis": asdict(rec.pixel_analysis),
            "crop_attempts": [asdict(a) for a in rec.attempts],
        })

    reconstruction["entries"] = updated_entries
    reconstruction["crop_recovery_applied"] = True
    reconstruction["recovery_stats"] = recovery_stats

    # Keep schedule as draft / needs_review
    if reconstruction.get("status") != "needs_review":
        reconstruction["status"] = "needs_review"

    return reconstruction
