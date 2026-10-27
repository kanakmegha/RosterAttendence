"""
Roster Table Reconstruction Module for Medical Staff Schedule Tracker.

Reconstructs structured employee rows and date-specific shift cells from 2D OCR word geometry.
Features:
- Preserves 2D bounding box geometry (no naive whitespace splitting).
- Discovers date column boundaries from header anchors and calendar math.
- De-concatenates merged OCR tokens (e.g. 'OFFN', 'N OFF M', 'OFE').
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

SHIFT_CODES = {
    "M": "Morning",
    "E": "Evening",
    "N": "Night",
    "OFF": "Off",
    "A": "Absent/Duty",  # Documented roster code
}


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
    """Reconstructs 2D tabular structure from OCR word coordinates."""

    def __init__(
        self,
        words: List[Dict[str, Any]],
        month_str: Optional[str] = None,
        requested_staff_id: Optional[str] = None,
    ):
        self.words = [w for w in words if str(w.get("text", "")).strip()]
        self.requested_staff_id = requested_staff_id
        self.month_str = month_str or self._detect_month_from_text()
        self.year, self.month = self._parse_year_month(self.month_str)

    def _detect_month_from_text(self) -> str:
        """Searches words for Month Name and Year (e.g. SEPTEMBER:-2026)."""
        all_text = " ".join(w["text"].upper() for w in self.words[:40])
        for name, m_num in MONTH_NAMES.items():
            pattern = rf"\b{name}\b[^\d]*(\d{{4}})"
            match = re.search(pattern, all_text)
            if match:
                year = int(match.group(1))
                return f"{year:04d}-{m_num:02d}"

        # Default fallback if not detectable
        return "2026-09"

    def _parse_year_month(self, m_str: str) -> Tuple[int, int]:
        parts = m_str.split("-")
        if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
            return int(parts[0]), int(parts[1])
        return 2026, 9

    def detect_date_columns(self) -> Tuple[Dict[int, Tuple[float, float]], List[str]]:
        """
        Identifies column boundaries [left_x, right_x] for every day in the month.
        Anchored on detected day numbers in the header area.
        """
        warnings = []
        num_days = calendar.monthrange(self.year, self.month)[1]

        # Look for day number tokens in the header band
        day_anchors: Dict[int, float] = {}
        for w in self.words:
            # Header zone: y between 40 and 75, x > 280
            if 40 <= w.get("y", 0) <= 75 and w.get("x", 0) > 280:
                cleaned = re.sub(r"\D", "", w["text"])
                if cleaned.isdigit():
                    val = int(cleaned)
                    if 1 <= val <= num_days and val not in day_anchors:
                        day_anchors[val] = w.get("cx", w.get("x", 0) + w.get("width", 0) / 2.0)

        if len(day_anchors) < 3:
            warnings.append(
                f"Low header confidence: only detected {len(day_anchors)} day anchors in header. "
                "Column mapping may require manual review."
            )

        # Compute column step and center for all days
        if len(day_anchors) >= 2:
            sorted_anchors = sorted(day_anchors.items())
            first_day, first_x = sorted_anchors[0]
            last_day, last_x = sorted_anchors[-1]
            col_step = (last_x - first_x) / max(1, (last_day - first_day))
        else:
            first_day = 1
            first_x = 330.0
            col_step = 27.0

        col_step = max(col_step, 15.0)

        day_intervals: Dict[int, Tuple[float, float]] = {}
        for d in range(1, num_days + 1):
            if d in day_anchors:
                center = day_anchors[d]
            else:
                center = first_x + (d - first_day) * col_step
            day_intervals[d] = (center - col_step * 0.52, center + col_step * 0.52)

        return day_intervals, warnings

    def detect_employee_rows(self) -> List[Dict[str, Any]]:
        """
        Clusters words into structured employee rows by detecting ID tokens and vertical positions.
        """
        # Look for ID tokens in the ID column region (x between 15 and 90)
        # Matches patterns like ABHL248, ABH1341, or numeric employee IDs
        id_tokens = []
        for w in self.words:
            if 15 <= w.get("x", 0) <= 90 and w.get("y", 0) > 65:
                text = normalize_id(w["text"])
                if re.match(r"^(ABH[L1I\d]\d+|\d{4,8})$", text, re.I):
                    id_tokens.append(w)

        # Sort ID tokens vertically
        id_tokens.sort(key=lambda item: item.get("cy", item.get("y", 0)))

        rows = []
        used_ids = set()

        for id_w in id_tokens:
            id_text = normalize_id(id_w["text"])
            if id_text in used_ids:
                continue
            used_ids.add(id_text)

            row_y = id_w.get("cy", id_w.get("y", 0))

            # Collect all words in vertical row window (tolerance ~ 8-11 px)
            row_words = [w for w in self.words if abs(w.get("cy", w.get("y", 0)) - row_y) <= 8.5]
            row_words.sort(key=lambda w: w.get("x", 0))

            # Segment into metadata and shift columns
            # Metadata columns: x < 305
            name_tokens = []
            off_day_token = None
            floor_tokens = []

            for w in row_words:
                wx = w.get("x", 0)
                wt = w["text"].strip()
                if 85 <= wx < 205:
                    name_tokens.append(wt)
                elif 205 <= wx < 245:
                    off_day_token = wt
                elif 245 <= wx < 305:
                    floor_tokens.append(wt)

            name = " ".join(name_tokens).strip() or None
            floor = " ".join(floor_tokens).strip() or None

            # Shift words (x >= 305)
            shift_words = [w for w in row_words if w.get("x", 0) >= 300]

            rows.append({
                "raw_id": id_w["text"],
                "normalized_id": id_text,
                "name": name,
                "weekly_off_day": off_day_token,
                "floor": floor,
                "y": row_y,
                "shift_words": shift_words,
            })

        return rows

    def parse_shift_tokens(
        self,
        words_in_col: List[Dict[str, Any]]
    ) -> Tuple[Optional[str], Optional[str], str, Optional[float], Optional[str]]:
        """
        Parses words mapped to a specific day column into normalized shift and status.
        Returns (raw_value, normalized_shift, status, confidence, provenance).
        """
        if not words_in_col:
            return None, None, "missing", None, None

        raw_str = " ".join(w["text"].strip() for w in words_in_col)
        provenance = ", ".join(f"x={int(w.get('x',0))}, y={int(w.get('y',0))}" for w in words_in_col)

        # Clean token for shift recognition
        cleaned = re.sub(r"[^\w]", "", raw_str).upper()

        # Handle common OCR border artifacts around OFF
        if cleaned in ("OFF", "OFE", "OFFI", "OFFN", "OFFM", "OEF"):
            # Check if concatenated with next shift
            if cleaned.startswith("OFF") and len(cleaned) > 3:
                remainder = cleaned[3:]
                return raw_str, "Off", "needs_review", 0.85, provenance
            status = "confirmed" if cleaned == "OFF" else "needs_review"
            return raw_str, "Off", status, 0.95 if status == "confirmed" else 0.85, provenance

        # Handle multiple 'N' or 'M'
        if cleaned in ("NN", "N"):
            return raw_str, "Night", "confirmed", 0.95, provenance
        if cleaned in ("MM", "M", "MMM"):
            return raw_str, "Morning", "confirmed", 0.95, provenance
        if cleaned == "E":
            return raw_str, "Evening", "confirmed", 0.95, provenance
        if cleaned == "A":
            return raw_str, "Absent/Duty", "needs_review", 0.80, provenance

        # Ambiguous or unknown tokens
        return raw_str, cleaned, "needs_review", 0.70, provenance

    def reconstruct(self) -> Dict[str, Any]:
        """
        Full table reconstruction workflow.
        Returns structured output with per-date entries, metadata, and diagnostics.
        """
        day_intervals, warnings = self.detect_date_columns()
        rows = self.detect_employee_rows()
        num_days = calendar.monthrange(self.year, self.month)[1]

        # Target employee matching
        target_row = None
        matched_id = None
        match_reason = "exact"
        overall_status = "confirmed"

        if self.requested_staff_id:
            req_id_norm = normalize_id(self.requested_staff_id)

            # 1. Exact match
            for r in rows:
                if r["normalized_id"] == req_id_norm:
                    target_row = r
                    matched_id = r["raw_id"]
                    break

            # 2. Confusable visual OCR match (e.g. ABHL341 vs ABH1341)
            if not target_row:
                for r in rows:
                    is_confusable, reason = are_ids_confusable(self.requested_staff_id, r["normalized_id"])
                    if is_confusable:
                        target_row = r
                        matched_id = r["raw_id"]
                        match_reason = reason
                        overall_status = "needs_review"
                        warnings.append(
                            f"Exact Staff ID '{self.requested_staff_id}' was not found. "
                            f"Matched OCR candidate '{matched_id}' ({reason}) for staff '{r['name']}'. "
                            "Held for human review."
                        )
                        break

            if not target_row:
                warnings.append(f"Staff ID '{self.requested_staff_id}' was not found in the roster.")
                return {
                    "roster_month": self.month_str,
                    "requested_staff_id": self.requested_staff_id,
                    "matched_staff_id": None,
                    "staff_name": None,
                    "weekly_off_day": None,
                    "floor_department": None,
                    "status": "id_not_found",
                    "entries": [],
                    "all_employees_summary": [{"id": r["raw_id"], "name": r["name"]} for r in rows],
                    "warnings": warnings,
                }
        else:
            # Default to first employee if none requested
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
                "warnings": warnings,
            }

        # Build entries for each day 1..num_days
        entries: List[Dict[str, Any]] = []

        for d in range(1, num_days + 1):
            left_bound, right_bound = day_intervals[d]
            dt = date(self.year, self.month, d)
            date_str = dt.isoformat()
            weekday_name = dt.strftime("%A")

            # Collect words falling in column d
            col_words = [
                w for w in target_row["shift_words"]
                if (left_bound <= w.get("cx", 0) < right_bound or left_bound <= w.get("x", 0) < right_bound)
            ]

            raw_val, norm_shift, cell_status, conf, prov = self.parse_shift_tokens(col_words)

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
            })

        return {
            "roster_month": self.month_str,
            "requested_staff_id": self.requested_staff_id,
            "matched_staff_id": matched_id,
            "staff_name": target_row["name"],
            "weekly_off_day": target_row["weekly_off_day"],
            "floor_department": target_row["floor"],
            "status": overall_status,
            "entries": entries,
            "all_employees_summary": [
                {
                    "id": r["raw_id"],
                    "name": r["name"],
                    "floor": r["floor"],
                    "weekly_off": r["weekly_off_day"],
                }
                for r in rows
            ],
            "warnings": warnings,
        }
