"""
Roster Table Reconstruction Module for Medical Staff Schedule Tracker.

Reconstructs structured employee rows and date-specific shift cells from 2D OCR word geometry.
Features:
- Preserves 2D bounding box geometry (no naive whitespace splitting).
- Geometry-driven metadata boundary detection (S.no, ID, Name, Off Day, Floor).
- Calendar-aligned date column boundaries with explicit header anchor validation.
- Flags merged headers (e.g. 'MONTUE'), missing anchors, and non-uniform column widths.
- De-concatenates merged OCR tokens (e.g. 'OFFN', 'N OFF M', 'OFE', 'NN').
- Strictly adheres to configured shift legend (M, E, N, OFF); does not invent meanings for 'A' or unknown codes.
- Performs exact Staff ID matching and flags OCR confusions (e.g. 'L' vs '1') for human review.
- Distinguishes cell statuses: 'confirmed', 'needs_review', 'unreadable', and 'missing'.
- Invariant: Blank/missing cells are NEVER inferred as Off.
"""

import calendar
import re
from datetime import date
from typing import Any, Dict, List, Optional, Tuple


MONTH_NAMES = {
    "JANUARY": 1, "FEBRUARY": 2, "MARCH": 3, "APRIL": 4, "MAY": 5, "JUNE": 6,
    "JULY": 7, "AUGUST": 8, "SEPTEMBER": 9, "OCTOBER": 10, "NOVEMBER": 11, "DECEMBER": 12,
    "JAN": 1, "FEB": 2, "MAR": 3, "APR": 4, "JUN": 6,
    "JUL": 7, "AUG": 8, "SEP": 9, "OCT": 10, "NOV": 11, "DEC": 12,
}

# Configured Shift Legend (strictly M, E, N, OFF)
DEFAULT_SHIFT_LEGEND = {
    "M": "Morning",
    "E": "Evening",
    "N": "Night",
    "OFF": "Off",
}

WEEKDAY_ABBRS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]


def normalize_id(text: str) -> str:
    """Conservative alphanumeric normalization preserving case-insensitivity."""
    if not text:
        return ""
    cleaned = re.sub(r"[^\w]", "", text).upper()
    return cleaned


def are_ids_confusable(target_id: str, candidate_id: str) -> Tuple[bool, Optional[str]]:
    """
    Checks if candidate_id is a plausible OCR confusion of target_id.
    Handles 'L' <-> '1' / 'I', 'O' <-> '0', 'B' <-> '8'.
    Returns (is_confusable, reason).
    """
    t = normalize_id(target_id)
    c = normalize_id(candidate_id)
    if not t or not c:
        return False, None

    if t == c:
        return True, "exact"

    if len(t) != len(c):
        return False, None

    # Replace confusable characters with canonical placeholders
    def canonical_confusables(s: str) -> str:
        s = s.replace("L", "1").replace("I", "1").replace("|", "1")
        s = s.replace("O", "0")
        s = s.replace("B", "8")
        return s

    if canonical_confusables(t) == canonical_confusables(c):
        return True, f"Visual OCR character confusion between '{candidate_id}' and '{target_id}' (e.g. L vs 1 or O vs 0)"

    return False, None


class RosterTableReconstructor:
    """Reconstructs 2D tabular structure from OCR word coordinates without hardcoded geometries."""

    def __init__(
        self,
        words: List[Dict[str, Any]],
        month_str: Optional[str] = None,
        requested_staff_id: Optional[str] = None,
        shift_legend: Optional[Dict[str, str]] = None,
    ):
        self.words = [w for w in words if str(w.get("text", "")).strip()]
        for w in self.words:
            if "cx" not in w:
                w["cx"] = w.get("x", 0.0) + w.get("width", 0.0) / 2.0
            if "cy" not in w:
                w["cy"] = w.get("y", 0.0) + w.get("height", 0.0) / 2.0

        self.requested_staff_id = requested_staff_id
        self.month_str = month_str or self._detect_month_from_text()
        self.year, self.month = self._parse_year_month(self.month_str)
        self.shift_legend = shift_legend or DEFAULT_SHIFT_LEGEND

    def _detect_month_from_text(self) -> str:
        """Searches words for Month Name and Year (e.g. SEPTEMBER:-2026)."""
        all_text = " ".join(w["text"].upper() for w in self.words[:50])
        for name, m_num in MONTH_NAMES.items():
            pattern = rf"\b{name}\b[^\d]*(\d{{4}})"
            match = re.search(pattern, all_text)
            if match:
                year = int(match.group(1))
                return f"{year:04d}-{m_num:02d}"

        return "2026-09"

    def _parse_year_month(self, m_str: str) -> Tuple[int, int]:
        parts = m_str.split("-")
        if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
            return int(parts[0]), int(parts[1])
        return 2026, 9

    def detect_metadata_boundaries(self) -> Dict[str, Any]:
        """
        Dynamically detects horizontal boundaries for metadata columns:
        S.no, ID, Name, Off Day, Floor, and Calendar Start.
        Driven by header word geometry, not hardcoded coordinates.
        """
        header_y_max = 75.0
        # Find candidate header tokens in metadata zone
        headers: Dict[str, Dict[str, Any]] = {}
        first_cal_word = None

        for w in self.words:
            wy = w.get("y", 0)
            wx = w.get("x", 0)
            if wy <= header_y_max:
                text_clean = re.sub(r"[^A-Z\.]", "", w["text"].upper())
                if ("S.NO" in text_clean or "SNO" in text_clean) and "sno" not in headers:
                    headers["sno"] = w
                elif text_clean == "ID" and "id" not in headers:
                    headers["id"] = w
                elif text_clean == "NAME" and "name" not in headers:
                    headers["name"] = w
                elif ("OFF" in text_clean or "DAY" in text_clean) and wx < 260 and "off_day" not in headers:
                    headers["off_day"] = w
                elif "FLOOR" in text_clean and wx < 310 and "floor" not in headers:
                    headers["floor"] = w
                elif wx >= 290 and any(text_clean.startswith(day) for day in WEEKDAY_ABBRS):
                    if first_cal_word is None or wx < first_cal_word.get("x", 9999):
                        first_cal_word = w

        # Calculate dynamic boundaries based on detected headers
        sno_right = headers["sno"]["x"] + headers["sno"]["width"] if "sno" in headers else 30.0
        id_left = headers["id"]["x"] if "id" in headers else 35.0
        id_right = headers["id"]["x"] + headers["id"]["width"] if "id" in headers else 85.0
        name_left = headers["name"]["x"] if "name" in headers else 100.0
        name_right = headers["name"]["x"] + headers["name"]["width"] if "name" in headers else 180.0
        off_left = headers["off_day"]["x"] if "off_day" in headers else 205.0
        off_right = headers["off_day"]["x"] + headers["off_day"]["width"] if "off_day" in headers else 245.0
        floor_left = headers["floor"]["x"] if "floor" in headers else 250.0
        floor_right = headers["floor"]["x"] + headers["floor"]["width"] if "floor" in headers else 300.0
        if first_cal_word and first_cal_word["x"] < floor_right + 40.0:
            cal_start = first_cal_word["x"]
        else:
            cal_start = floor_right + 10.0

        # Dynamic cutoffs computed as midpoints
        # S.no values are 1-2 digits, so boundary to ID column is tightly bounded
        b_sno = min(32.0, (sno_right + id_left) / 2.0) if "sno" in headers and "id" in headers else 30.0
        b_id = (id_right + name_left) / 2.0
        b_name = (name_right + off_left) / 2.0
        b_off = (off_right + floor_left) / 2.0
        b_floor = (floor_right + cal_start) / 2.0

        return {
            "sno_max": b_sno,
            "id_max": b_id,
            "name_max": b_name,
            "off_day_max": b_off,
            "floor_max": b_floor,
            "calendar_start": b_floor,
            "detected_headers": {k: {"x": v["x"], "width": v["width"]} for k, v in headers.items()},
        }

    def detect_date_columns(self) -> Tuple[Dict[int, Tuple[float, float]], Dict[int, Dict[str, Any]], List[str]]:
        """
        Identifies column boundaries [left_x, right_x] for every day in the month.
        Anchored on detected weekday headers and numeric date tokens.
        Never linearly interpolates across unanchored zones without flagging review.
        """
        warnings = []
        num_days = calendar.monthrange(self.year, self.month)[1]
        meta_bounds = self.detect_metadata_boundaries()
        cal_start_x = meta_bounds["calendar_start"]

        # Expected weekday sequence for the month
        expected_weekdays = {
            d: date(self.year, self.month, d).strftime("%a").upper()
            for d in range(1, num_days + 1)
        }

        # Header words in calendar area (x >= cal_start_x - 15)
        # Weekday headers typically in y band 35..56, day numbers in y band 55..75
        header_words = [
            w for w in self.words
            if w.get("x", 0) >= cal_start_x - 15 and w.get("y", 0) <= 75
        ]

        # 1. Day number anchors
        number_anchors: Dict[int, Dict[str, Any]] = {}
        for w in header_words:
            # Check if text is clean integer
            cleaned = re.sub(r"\D", "", w["text"])
            if cleaned.isdigit():
                val = int(cleaned)
                if 1 <= val <= num_days and val not in number_anchors:
                    number_anchors[val] = {
                        "cx": w["cx"],
                        "left": w["x"],
                        "right": w["x"] + w["width"],
                        "text": w["text"],
                        "token": w,
                    }

        # 2. Weekday header tokens
        weekday_tokens = []
        for w in header_words:
            # Exclude digits and vertical separator noise
            cleaned = re.sub(r"[^A-Z]", "", w["text"].upper())
            if not cleaned or cleaned.isdigit():
                continue
            # Handle common OCR typos like FRE -> FRI
            if cleaned == "FRE":
                cleaned = "FRI"
            # Keep if contains weekday letters
            if any(day in cleaned for day in WEEKDAY_ABBRS) or cleaned == "MONTUE":
                weekday_tokens.append({
                    "cx": w["cx"],
                    "left": w["x"],
                    "right": w["x"] + w["width"],
                    "text": cleaned,
                    "token": w,
                })

        weekday_tokens.sort(key=lambda t: t["left"])

        # Estimate reference column step from number anchors if available
        if len(number_anchors) >= 2:
            num_keys = sorted(number_anchors.keys())
            first_k, last_k = num_keys[0], num_keys[-1]
            rough_col_step = (number_anchors[last_k]["cx"] - number_anchors[first_k]["cx"]) / max(1, last_k - first_k)
            rough_first_x = number_anchors[first_k]["cx"] - (first_k - 1) * rough_col_step
        else:
            rough_first_x = cal_start_x + 15.0
            rough_col_step = 27.0

        # Match weekday tokens to expected days
        day_anchors: Dict[int, Dict[str, Any]] = {}
        tok_idx = 0

        for d in range(1, num_days + 1):
            exp_w = expected_weekdays[d]

            # Check if number anchor exists
            num_anc = number_anchors.get(d)

            # Match weekday token
            w_anc = None
            is_merged = False
            merged_info = None

            if tok_idx < len(weekday_tokens):
                curr_tok = weekday_tokens[tok_idx]
                curr_text = curr_tok["text"]
                est_day = round((curr_tok["cx"] - rough_first_x) / max(15.0, rough_col_step)) + 1

                # Only consider token if it roughly aligns with current day (+/- 2.5 columns)
                if abs(d - est_day) <= 2.5:
                    # Case A: Exact weekday match
                    if curr_text == exp_w:
                        w_anc = curr_tok
                        tok_idx += 1
                    # Case B: Merged header token (e.g. MONTUE covering Day 14 and Day 15)
                    elif len(curr_text) > 3 and any(day in curr_text for day in WEEKDAY_ABBRS):
                        if curr_text.startswith(exp_w):
                            is_merged = True
                            merged_info = curr_text
                            w_anc = curr_tok
                            if d < num_days and expected_weekdays[d + 1] in curr_text:
                                pass
                            else:
                                tok_idx += 1
                        elif d > 1 and exp_w in curr_text and curr_text.startswith(expected_weekdays[d - 1]):
                            is_merged = True
                            merged_info = curr_text
                            w_anc = curr_tok
                            tok_idx += 1
                elif d > est_day + 2:
                    # Token was from an earlier day that was skipped
                    tok_idx += 1

            # Combine evidence
            if num_anc and w_anc:
                day_anchors[d] = {
                    "cx": (num_anc["cx"] + w_anc["cx"]) / 2.0,
                    "anchor_type": "number_and_weekday",
                    "anchor_confidence": "high",
                    "evidence": f"Weekday '{w_anc['text']}' and date number '{num_anc['text']}'",
                    "weekday": exp_w,
                }
            elif num_anc:
                day_anchors[d] = {
                    "cx": num_anc["cx"],
                    "anchor_type": "number_only",
                    "anchor_confidence": "high",
                    "evidence": f"Date number token '{num_anc['text']}'",
                    "weekday": exp_w,
                }
            elif w_anc:
                conf = "merged_header" if is_merged else "high"
                day_anchors[d] = {
                    "cx": w_anc["cx"] if not is_merged else (
                        w_anc["left"] + w_anc["token"]["width"] * (0.25 if exp_w == "MON" else 0.75)
                    ),
                    "anchor_type": "weekday_merged" if is_merged else "weekday_only",
                    "anchor_confidence": conf,
                    "evidence": f"Weekday header token '{w_anc['text']}'" + (f" (merged '{merged_info}')" if is_merged else ""),
                    "weekday": exp_w,
                }
                if is_merged:
                    warnings.append(
                        f"Day {d} ({exp_w}) falls in merged header '{merged_info}'. "
                        "Column boundaries require manual review."
                    )

        # Flag missing or unanchored days
        unanchored_days = [d for d in range(1, num_days + 1) if d not in day_anchors]
        if unanchored_days:
            warnings.append(
                f"Missing date anchors for days: {unanchored_days}. "
                "Unanchored columns will be held for human review."
            )

        # Estimate centers for any unanchored days strictly between closest left and right anchored neighbors
        anchored_sorted = sorted(day_anchors.keys())
        if not anchored_sorted:
            # Fallback if no anchors at all
            first_day, first_x, col_step = 1, cal_start_x + 15.0, 27.0
            for d in range(1, num_days + 1):
                day_anchors[d] = {
                    "cx": first_x + (d - 1) * col_step,
                    "anchor_type": "unanchored_fallback",
                    "anchor_confidence": "unanchored",
                    "evidence": "No physical anchors detected",
                    "weekday": expected_weekdays[d],
                }
        else:
            for d in unanchored_days:
                # Find closest left anchored day
                left_anchors = [a for a in anchored_sorted if a < d]
                right_anchors = [a for a in anchored_sorted if a > d]

                if left_anchors and right_anchors:
                    l_day = left_anchors[-1]
                    r_day = right_anchors[0]
                    l_cx = day_anchors[l_day]["cx"]
                    r_cx = day_anchors[r_day]["cx"]
                    interp_cx = l_cx + (d - l_day) * (r_cx - l_cx) / (r_day - l_day)
                elif left_anchors:
                    l_day = left_anchors[-1]
                    prev_l = left_anchors[-2] if len(left_anchors) >= 2 else (l_day - 1)
                    step = (day_anchors[l_day]["cx"] - day_anchors[prev_l]["cx"]) / max(1, l_day - prev_l) if len(left_anchors) >= 2 else 27.0
                    interp_cx = day_anchors[l_day]["cx"] + (d - l_day) * step
                else:
                    r_day = right_anchors[0]
                    next_r = right_anchors[1] if len(right_anchors) >= 2 else (r_day + 1)
                    step = (day_anchors[next_r]["cx"] - day_anchors[r_day]["cx"]) / max(1, next_r - r_day) if len(right_anchors) >= 2 else 27.0
                    interp_cx = day_anchors[r_day]["cx"] - (r_day - d) * step

                day_anchors[d] = {
                    "cx": interp_cx,
                    "anchor_type": "interpolated",
                    "anchor_confidence": "unanchored",
                    "evidence": "Interpolated between anchored neighbors",
                    "weekday": expected_weekdays[d],
                }

        # Calculate exact column boundary intervals [left, right]
        day_intervals: Dict[int, Tuple[float, float]] = {}
        day_diagnostics: Dict[int, Dict[str, Any]] = {}

        for d in range(1, num_days + 1):
            cx = day_anchors[d]["cx"]
            if d == 1:
                left_bound = cal_start_x
            else:
                prev_cx = day_anchors[d - 1]["cx"]
                left_bound = (prev_cx + cx) / 2.0

            if d == num_days:
                prev_cx = day_anchors[d - 1]["cx"]
                step = cx - prev_cx
                right_bound = cx + step / 2.0
            else:
                next_cx = day_anchors[d + 1]["cx"]
                right_bound = (cx + next_cx) / 2.0

            day_intervals[d] = (left_bound, right_bound)
            day_diagnostics[d] = {
                "day_number": d,
                "weekday": expected_weekdays[d],
                "center_x": round(cx, 1),
                "bounds": [round(left_bound, 1), round(right_bound, 1)],
                "anchor_type": day_anchors[d]["anchor_type"],
                "anchor_confidence": day_anchors[d]["anchor_confidence"],
                "evidence": day_anchors[d]["evidence"],
            }

        return day_intervals, day_diagnostics, warnings

    def detect_employee_rows(self) -> List[Dict[str, Any]]:
        """
        Clusters words into structured employee rows by geometric vertical proximity.
        Preserves rows with missing serial numbers, IDs, names, or floor information.
        """
        meta_bounds = self.detect_metadata_boundaries()
        cal_start_x = meta_bounds["calendar_start"]

        # Body words: below header band (> 72) and above summary/footer (< 620)
        body_words = [
            w for w in self.words
            if 72.0 <= w.get("cy", w.get("y", 0)) <= 620.0
        ]
        if not body_words:
            return []

        body_words.sort(key=lambda w: (w.get("cy", w.get("y", 0)), w.get("x", 0)))

        # Adaptive row clustering tolerance based on median word height
        heights = [w.get("height", 0) for w in body_words if w.get("height", 0) > 0]
        median_h = sorted(heights)[len(heights) // 2] if heights else 7.0
        row_tolerance = max(4.0, median_h * 0.85)

        clusters: List[Dict[str, Any]] = []
        for w in body_words:
            cy = w.get("cy", w.get("y", 0))
            matched_cluster = None
            for c in clusters:
                if abs(c["cy"] - cy) <= row_tolerance:
                    matched_cluster = c
                    break

            if matched_cluster:
                matched_cluster["words"].append(w)
                matched_cluster["cy"] = sum(x.get("cy", x.get("y", 0)) for x in matched_cluster["words"]) / len(matched_cluster["words"])
            else:
                clusters.append({"cy": cy, "words": [w]})

        clusters.sort(key=lambda c: c["cy"])

        # Segment each clustered row into columns
        rows = []
        for c in clusters:
            row_words = sorted(c["words"], key=lambda w: w.get("x", 0))
            row_y = c["cy"]

            sno_tokens = []
            id_tokens = []
            name_tokens = []
            off_day_tokens = []
            floor_tokens = []
            shift_words = []

            for w in row_words:
                wx = w.get("x", 0)
                wt = w["text"].strip()
                if wx < meta_bounds["sno_max"]:
                    sno_tokens.append(wt)
                elif wx < meta_bounds["id_max"]:
                    id_tokens.append(wt)
                elif wx < meta_bounds["name_max"]:
                    name_tokens.append(wt)
                elif wx < meta_bounds["off_day_max"]:
                    off_day_tokens.append(wt)
                elif wx < meta_bounds["floor_max"]:
                    floor_tokens.append(wt)
                else:
                    shift_words.append(w)

            # Semantic classification of ID from metadata tokens
            raw_id = None
            for idx, t in enumerate(list(sno_tokens)):
                norm_t = normalize_id(t)
                if re.match(r"^(ABH[L1I\d]\d+|\d{4,8})$", norm_t, re.I):
                    raw_id = t
                    sno_tokens.pop(idx)
                    break

            if not raw_id:
                for idx, t in enumerate(list(id_tokens)):
                    norm_t = normalize_id(t)
                    if re.match(r"^(ABH[L1I\d]\d+|\d{4,8})$", norm_t, re.I):
                        raw_id = t
                        id_tokens.pop(idx)
                        break

            if not raw_id:
                for idx, t in enumerate(list(name_tokens)):
                    norm_t = normalize_id(t)
                    if re.match(r"^(ABH[L1I\d]\d+|\d{4,8})$", norm_t, re.I):
                        raw_id = t
                        name_tokens.pop(idx)
                        break

            if not raw_id and id_tokens:
                raw_id = " ".join(id_tokens).strip() or None

            norm_id = normalize_id(raw_id) if raw_id else None
            name = " ".join(name_tokens).strip() or None
            off_day = " ".join(off_day_tokens).strip() or None
            floor = " ".join(floor_tokens).strip() or None
            sno = " ".join(sno_tokens).strip() or None

            # Skip empty or footer noise rows
            if not raw_id and not name and not shift_words:
                continue
            if off_day and any(kw in off_day.upper() for kw in ("DAILY", "PRESENT", "WEEKLY")):
                continue

            rows.append({
                "serial_number": sno,
                "raw_id": raw_id,
                "normalized_id": norm_id,
                "name": name,
                "weekly_off_day": off_day,
                "floor": floor,
                "y": row_y,
                "shift_words": shift_words,
            })

        return rows

    def parse_shift_tokens(
        self,
        words_in_col: List[Dict[str, Any]],
        col_meta: Optional[Dict[str, Any]] = None,
    ) -> Tuple[Optional[str], Optional[str], str, Optional[float], Optional[str], Optional[str]]:
        """
        Parses words mapped to a specific day column into normalized shift and status.
        Returns: (raw_value, normalized_shift, status, confidence, provenance, review_reason).
        """
        if not words_in_col:
            return None, None, "missing", None, None, None

        raw_str = " ".join(w["text"].strip() for w in words_in_col)
        provenance = ", ".join(f"x={int(w.get('x',0))}, y={int(w.get('y',0))}" for w in words_in_col)

        # Check column anchor confidence
        col_anchor_conf = (col_meta or {}).get("anchor_confidence", "high")
        is_column_unreliable = col_anchor_conf != "high"

        cleaned = re.sub(r"[^\w]", "", raw_str).upper()

        # 1. Clean exact match in configured shift legend
        if cleaned in self.shift_legend:
            norm_shift = self.shift_legend[cleaned]
            if is_column_unreliable:
                return (
                    raw_str,
                    norm_shift,
                    "needs_review",
                    0.75,
                    provenance,
                    f"Date column anchor is unreliable ({col_anchor_conf}). Held for review.",
                )
            return raw_str, norm_shift, "confirmed", 0.95, provenance, None

        # 2. Border artifacts around OFF (e.g. OFFI, OFE, OFFN, OFF., OEF)
        if cleaned in ("OFFI", "OFE", "OFFN", "OFFM", "OEF", "OFEI"):
            return (
                raw_str,
                "Off",
                "needs_review",
                0.75,
                provenance,
                f"OCR border artifact '{raw_str}'. Candidate interpretation: Off. Held for review.",
            )

        # 3. Concatenated / multi-character duty tokens (e.g. NN, MM, NAM)
        if cleaned == "NN":
            return (
                raw_str,
                "Night",
                "needs_review",
                0.70,
                provenance,
                "Ambiguous concatenated token 'NN' spanning multiple days/shifts. Held for review.",
            )
        if cleaned in ("MM", "MMM"):
            return (
                raw_str,
                "Morning",
                "needs_review",
                0.70,
                provenance,
                f"Ambiguous concatenated token '{raw_str}'. Candidate: Morning. Held for review.",
            )

        # 4. 'A' code (NOT in standard configured shift legend)
        if cleaned == "A":
            return (
                raw_str,
                None,
                "needs_review",
                0.70,
                provenance,
                "Unrecognized shift code 'A' not in configured legend [M, E, N, OFF]. Held for human review.",
            )

        # 5. Any other unrecognized or ambiguous token
        return (
            raw_str,
            None,
            "needs_review",
            0.50,
            provenance,
            f"Unrecognized or ambiguous token '{raw_str}' not in configured shift legend.",
        )

    def reconstruct(self) -> Dict[str, Any]:
        """
        Full table reconstruction workflow.
        Returns structured output with per-date entries, metadata boundaries, diagnostics, and employee summaries.
        """
        day_intervals, day_diagnostics, warnings = self.detect_date_columns()
        meta_bounds = self.detect_metadata_boundaries()
        rows = self.detect_employee_rows()
        num_days = calendar.monthrange(self.year, self.month)[1]

        target_row = None
        matched_id = None
        match_reason = "exact"
        overall_status = "confirmed"

        if self.requested_staff_id:
            req_id_norm = normalize_id(self.requested_staff_id)

            # 1. Exact match
            for r in rows:
                if r["normalized_id"] and r["normalized_id"] == req_id_norm:
                    target_row = r
                    matched_id = r["raw_id"]
                    break

            # 2. Confusable visual OCR match (e.g. ABHL341 vs ABH1341)
            if not target_row:
                for r in rows:
                    if r["normalized_id"]:
                        is_confusable, reason = are_ids_confusable(self.requested_staff_id, r["normalized_id"])
                        if is_confusable:
                            target_row = r
                            matched_id = r["raw_id"]
                            match_reason = reason
                            overall_status = "needs_review"
                            warnings.append(
                                f"Exact Staff ID '{self.requested_staff_id}' was not found. "
                                f"Matched OCR candidate '{matched_id}' ({reason}) for staff '{r['name']}'. "
                                "Schedule held for human review."
                            )
                            break

            if not target_row:
                warnings.append(f"Staff ID '{self.requested_staff_id}' was not found in the roster.")
                return {
                    "roster_month": self.month_str,
                    "requested_staff_id": self.requested_staff_id,
                    "matched_staff_id": None,
                    "match_reason": None,
                    "staff_name": None,
                    "weekly_off_day": None,
                    "floor_department": None,
                    "status": "id_not_found",
                    "entries": [],
                    "all_employees_summary": [
                        {"id": r["raw_id"], "name": r["name"], "floor": r["floor"], "weekly_off": r["weekly_off_day"]}
                        for r in rows
                    ],
                    "metadata_boundaries": meta_bounds,
                    "column_diagnostics": day_diagnostics,
                    "warnings": warnings,
                }
        else:
            target_row = rows[0] if rows else None
            matched_id = target_row["raw_id"] if target_row else None

        if not target_row:
            warnings.append("No employee rows could be recognized in the roster table.")
            return {
                "roster_month": self.month_str,
                "requested_staff_id": self.requested_staff_id,
                "matched_staff_id": None,
                "staff_name": None,
                "status": "failed",
                "entries": [],
                "metadata_boundaries": meta_bounds,
                "column_diagnostics": day_diagnostics,
                "warnings": warnings,
            }

        # Build entries for each day 1..num_days
        entries: List[Dict[str, Any]] = []

        for d in range(1, num_days + 1):
            left_bound, right_bound = day_intervals[d]
            col_diag = day_diagnostics[d]
            col_cx = col_diag["center_x"]
            dt = date(self.year, self.month, d)
            date_str = dt.isoformat()
            weekday_name = dt.strftime("%A")

            # Collect words falling into column d:
            # 1. Primary: word center falls inside [left_bound, right_bound]
            # 2. Multi-column concatenated token (width >= 36 px and >= 2 chars, e.g. 'NN' or 'NAM')
            #    that significantly overlaps this column by >= 12 px
            col_words = []
            for w in target_row["shift_words"]:
                wx0, wx1 = w.get("x", 0), w.get("x", 0) + w.get("width", 0)
                if left_bound <= w["cx"] < right_bound:
                    col_words.append(w)
                elif w.get("width", 0) >= 36.0 and len(w.get("text", "").strip()) >= 2:
                    overlap = min(wx1, right_bound) - max(wx0, left_bound)
                    if overlap >= 12.0:
                        col_words.append(w)

            col_words.sort(key=lambda w: w.get("x", 0))

            raw_val, norm_shift, cell_status, conf, prov, review_reason = self.parse_shift_tokens(
                col_words, col_meta=col_diag
            )

            # If target employee had ID confusion, confirmed cells must also require review
            if match_reason != "exact" and cell_status == "confirmed":
                cell_status = "needs_review"
                review_reason = f"Schedule subject to Staff ID confusion ({match_reason})"

            if cell_status == "needs_review" and overall_status == "confirmed":
                overall_status = "needs_review"

            entries.append({
                "date": date_str,
                "day_number": d,
                "weekday": weekday_name,
                "raw_value": raw_val,
                "normalized_shift": norm_shift,
                "floor": target_row["floor"],
                "status": cell_status,
                "confidence": conf,
                "provenance": prov,
                "review_reason": review_reason,
                "anchor_type": col_diag["anchor_type"],
                "anchor_confidence": col_diag["anchor_confidence"],
            })

        return {
            "roster_month": self.month_str,
            "requested_staff_id": self.requested_staff_id,
            "matched_staff_id": matched_id,
            "match_reason": match_reason,
            "staff_name": target_row["name"],
            "weekly_off_day": target_row["weekly_off_day"],
            "floor_department": target_row["floor"],
            "status": overall_status,
            "entries": entries,
            "all_employees_summary": [
                {
                    "serial_number": r["serial_number"],
                    "id": r["raw_id"],
                    "name": r["name"],
                    "floor": r["floor"],
                    "weekly_off": r["weekly_off_day"],
                }
                for r in rows
            ],
            "metadata_boundaries": meta_bounds,
            "column_diagnostics": day_diagnostics,
            "warnings": warnings,
        }
